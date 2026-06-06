package com.clickplan.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 7;
    private static final int SAMPLE_RATE = 48000;
    private static final String PREF = "clickdose_prefs";
    private enum Mode { IDLE, COUNTING, CALIBRATING_NOISE, CALIBRATING_CLICKS, MEASURE_FULL }

    private volatile boolean audioActive = false;
    private volatile Mode mode = Mode.IDLE;
    private Thread audioThread;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable measureDoneRunnable = this::onMeasurePause;

    private int count = 0;
    private long lastClickNs = 0;
    private double noiseAvgJump = 180.0;
    private double calibratedClickPeak = 2600.0;
    private double threshold = 1000.0;
    private int cooldownMs = 80;
    private double thresholdFactor = 0.38;
    private double latestPeak = 0.0;
    private double latestAvgJump = 0.0;
    private long calibrationStartNs = 0;
    private final ArrayList<Double> clickCalibrationPeaks = new ArrayList<>();
    private final ArrayList<Long> clickCalibrationTimes = new ArrayList<>();

    // ── Data model ───────────────────────────────────────────────────────────
    // Clicks are authoritative. mg values are always derived.
    private String drugName = "";          // e.g. "Wegovy", "Ozempic"
    private double strengthMg = 2.4;       // full dose mg per pen
    private int dosesPerPen = 4;           // doses per pen
    private int fullClickDoses = 74;       // clicks per full dose
    private int remainingClicks = 296;     // remaining clicks in current pen
    private double targetMg = 1.2;        // current week target (derived)
    private int targetClicks = 37;         // current week clicks (authoritative)
    private int penNumber = 1;             // pen counter — increments at new pen

    // ── Plan model ───────────────────────────────────────────────────────────
    // Dose changes only happen when starting a new pen.
    private int currentPlanClicks = 37;    // clicks per week for current pen
    private int targetPlanClicks = 1;      // absolute floor — never reduce below this
    private boolean planActive = false;    // true once user has set a plan

    private boolean targetReachedBuzzed = false;
    private String language = "en";
    private String penName = "";
    private String injectionHistory = "";

    private final int bg = Color.rgb(9,9,11);
    private final int card = Color.rgb(22,22,25);
    private final int card2 = Color.rgb(31,31,36);
    private final int ink = Color.rgb(246,246,248);
    private final int muted = Color.rgb(153,153,162);
    private final int track = Color.rgb(43,43,49);
    private final int accent = Color.rgb(124,255,178);
    private final int warn = Color.rgb(255,184,107);
    private final int blue = Color.rgb(126,147,255);

    private TextView restBigView, restSubView, centerMainView, centerSubView, centerTinyView, lastDoseView;
    private TextView leftStatView, rightStatView;
    private SegmentedRingView penRing, doseRing;
    private Button primaryButton;

    private TextView fullCountView, fullSubView, fullHintView;
    private SegmentedRingView fullRing;
    private Button fullPrimaryButton, fullMinusButton, fullPlusButton, fullBackButton;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        loadPrefs();
        recomputeTarget();
        recomputeThreshold();
        int savedCount = getSharedPreferences(PREF, MODE_PRIVATE).getInt("countInProgress", -1);
        if (savedCount > 0) {
            count = savedCount;
            mode = Mode.COUNTING;
            buildCounterUi(tr("set_dose"), targetClicks + " " + tr("clicks_target") + String.format(Locale.US, "%.2f mg", targetMg));
        } else {
            buildHomeUi();
        }
        refreshUi();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        if (isFirstLaunch()) showOnboarding();
    }

    private boolean isFirstLaunch() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("firstLaunch", true);
    }

    private void showOnboarding() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("firstLaunch", false).apply();
        LinearLayout box = dialogBox();
        box.addView(text(tr("onboarding_title"), 26, ink, true));
        box.addView(text(tr("onboarding_msg"), 15, muted, false));
        TextView ringsInfo = text(tr("onboarding_rings"), 13, muted, false);
        ringsInfo.setPadding(0, dp(10), 0, 0);
        box.addView(ringsInfo);
        TextView disclaimer = text(tr("onboarding_disclaimer"), 12, warn, false);
        disclaimer.setPadding(0, dp(12), 0, 0);
        box.addView(disclaimer);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(tr("setup_pen"), (d, w) -> showProfileDialogWithPlannerNext())
            .show();
    }

    private void showProfileDialogWithPlannerNext() {
        LinearLayout box = dialogBox(); box.addView(text(tr("profile_title"), 24, ink, true));
        box.addView(text(tr("profile_setup_hint"), 13, muted, false));
        EditText name = inputText(penName.isEmpty() ? tr("my_pen") : penName, tr("pen_name_hint"));
        EditText strength = input(String.valueOf(strengthMg), tr("full_dose_hint"));
        EditText doses = input(String.valueOf(dosesPerPen), tr("doses_per_pen"));
        EditText full = input(String.valueOf(fullClickDoses), tr("clicks_per_dose"));
        TextView clicksHint = text(tr("pen_clicks_hint"), 11, muted, false);
        clicksHint.setPadding(0, dp(2), 0, dp(6));
        box.addView(labelled(tr("pen_name_label"), name));
        box.addView(labelled(tr("full_dose_label"), strength));
        box.addView(labelled(tr("doses_per_pen"), doses));
        box.addView(labelled(tr("clicks_per_dose"), full));
        box.addView(clicksHint);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar).setView(box)
            .setCancelable(false)
            .setPositiveButton(tr("done"), (d, w) -> {
                penName = name.getText().toString().trim();
                strengthMg = readDouble(strength, strengthMg);
                dosesPerPen = Math.max(1, readInt(doses, dosesPerPen));
                fullClickDoses = Math.max(1, readInt(full, fullClickDoses));
                remainingClicks = fullClickDoses * dosesPerPen;
                targetMg = strengthMg / dosesPerPen;
                recomputeTarget();
                currentPlanClicks = targetClicks;
                // targetPlanClicks is the absolute floor — don't reset it on recalibration
                planActive = true;
                savePrefs(); buildHomeUi(); refreshUi();
                new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
                    .setTitle(tr("calibrate_prompt_title"))
                    .setMessage(tr("calibrate_prompt_msg"))
                    .setPositiveButton(tr("calibrate"), (d2, w2) -> startCalibrationFullscreen())
                    .setNegativeButton(tr("skip_for_now"), null)
                    .show();
            })
            .show();
    }

    private void buildHomeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(22), dp(20), dp(18) + bottomInsetPx());
        root.setClipToPadding(false);
        setContentView(root);

        // ── Header ──────────────────────────────────────────────────────────
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("ClickPlan", 26, ink, true);
        title.setIncludeFontPadding(false);
        String penLabel = (penName.isEmpty() ? tr("my_pen") : penName) + "  ✏";
        TextView sub = text(penLabel, 13, muted, false);
        sub.setClickable(true);
        sub.setOnClickListener(v -> showProfileDialog());
        titleBox.addView(title);
        titleBox.addView(sub);
        top.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        // ── Stage card ───────────────────────────────────────────────────────
        LinearLayout stage = roundedBox(card, 30, dp(18));
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-1, 0, 1);
        stageLp.setMargins(0, dp(14), 0, dp(12));
        root.addView(stage, stageLp);

        // "THIS WEEK" label
        TextView weekLabel = text(tr("this_week"), 11, muted, true);
        weekLabel.setLetterSpacing(0.14f);
        weekLabel.setGravity(Gravity.CENTER);
        stage.addView(weekLabel, new LinearLayout.LayoutParams(-1, -2));

        // Ring with dominant number in center
        FrameLayout frame = new FrameLayout(this);
        penRing = new SegmentedRingView(this);
        penRing.strokeDp = 13;
        penRing.trackColor = track;
        penRing.progressColor = accent;
        frame.addView(penRing, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        // Center content: big clicks number + label + mg
        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        centerMainView = text("37", 72, ink, true);
        centerMainView.setGravity(Gravity.CENTER);
        centerMainView.setIncludeFontPadding(false);
        centerSubView = text(tr("clicks"), 18, accent, true);
        centerSubView.setGravity(Gravity.CENTER);
        centerTinyView = text("", 13, muted, false);
        centerTinyView.setGravity(Gravity.CENTER);
        center.addView(centerMainView);
        center.addView(centerSubView);
        center.addView(centerTinyView);
        frame.addView(center, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        stage.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));

        // ── Secondary info row ───────────────────────────────────────────────
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        leftStatView = statCard("");
        rightStatView = statCard("");
        stats.addView(leftStatView, new LinearLayout.LayoutParams(0, dp(66), 1));
        Space sp = new Space(this);
        stats.addView(sp, new LinearLayout.LayoutParams(dp(10), 1));
        stats.addView(rightStatView, new LinearLayout.LayoutParams(0, dp(66), 1));
        stage.addView(stats, new LinearLayout.LayoutParams(-1, -2));

        lastDoseView = text("", 12, muted, false);
        lastDoseView.setGravity(Gravity.CENTER);
        lastDoseView.setPadding(0, dp(6), 0, dp(2));
        stage.addView(lastDoseView, new LinearLayout.LayoutParams(-1, -2));

        // ── Primary action button ────────────────────────────────────────────
        primaryButton = pillButton("", accent, Color.rgb(4, 23, 14));
        primaryButton.setTextSize(17);
        primaryButton.setOnClickListener(v -> startDoseCountingFullscreen());
        root.addView(primaryButton, new LinearLayout.LayoutParams(-1, dp(58)));

        // ── Bottom nav (3 items — no Plan) ───────────────────────────────────
        LinearLayout bottom = roundedBox(Color.rgb(18, 18, 21), 28, dp(5));
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        String[] navItems = {tr("calibrate"), tr("history"), tr("more")};
        for (String n : navItems) {
            Button b = ghostButton(n);
            b.setTextSize(12);
            if (n.equals(tr("calibrate")))   b.setOnClickListener(v -> startCalibrationFullscreen());
            else if (n.equals(tr("history"))) b.setOnClickListener(v -> showHistoryDialog());
            else                               b.setOnClickListener(v -> showMoreDialog());
            bottom.addView(b, new LinearLayout.LayoutParams(0, dp(50), 1));
        }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(60));
        blp.setMargins(0, dp(12), 0, 0);
        root.addView(bottom, blp);
    }

    private void buildCounterUi(String title, String subtitle) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(22), dp(20), dp(18) + bottomInsetPx());
        root.setClipToPadding(false);
        setContentView(root);

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        fullBackButton = ghostButton("‹"); fullBackButton.setTextSize(34); fullBackButton.setOnClickListener(v -> { mode=Mode.IDLE; stopAudio(); getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("countInProgress", -1).apply(); buildHomeUi(); refreshUi(); });
        top.addView(fullBackButton, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout texts = new LinearLayout(this); texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 23, ink, true); t.setIncludeFontPadding(false);
        TextView s = text(subtitle, 13, muted, false);
        texts.addView(t); texts.addView(s);
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        FrameLayout frame = new FrameLayout(this);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, 0, 1); flp.setMargins(0, dp(18), 0, dp(12));
        fullRing = new SegmentedRingView(this);
        fullRing.strokeDp = 14; fullRing.trackColor = track; fullRing.progressColor = accent; fullRing.segments = Math.max(8, Math.min(96, targetClicks));
        frame.addView(fullRing, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        LinearLayout center = new LinearLayout(this); center.setOrientation(LinearLayout.VERTICAL); center.setGravity(Gravity.CENTER);
        fullCountView = text("0", 78, ink, true); fullCountView.setGravity(Gravity.CENTER); fullCountView.setIncludeFontPadding(false);
        fullSubView = text("/ " + targetClicks, 28, muted, true); fullSubView.setGravity(Gravity.CENTER);
        fullHintView = text(tr("turn_to_goal"), 15, muted, false); fullHintView.setGravity(Gravity.CENTER);
        center.addView(fullCountView); center.addView(fullSubView); center.addView(fullHintView);
        frame.addView(center, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(frame, flp);

        LinearLayout adjust = new LinearLayout(this); adjust.setOrientation(LinearLayout.HORIZONTAL); adjust.setGravity(Gravity.CENTER);
        fullMinusButton = smallRoundButton("−1"); fullMinusButton.setOnClickListener(v -> { count=Math.max(0,count-1); targetReachedBuzzed=false; refreshUi(); });
        fullPrimaryButton = pillButton(tr("stop_counting"), card2, ink); fullPrimaryButton.setTextSize(17); fullPrimaryButton.setOnClickListener(v -> handleFullPrimaryPress());
        fullPlusButton = smallRoundButton("+1"); fullPlusButton.setOnClickListener(v -> { count++; checkTargetFeedback(); refreshUi(); });
        adjust.addView(fullMinusButton, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, dp(60), 1); mid.setMargins(dp(12),0,dp(12),0);
        adjust.addView(fullPrimaryButton, mid);
        adjust.addView(fullPlusButton, new LinearLayout.LayoutParams(dp(60), dp(60)));
        root.addView(adjust);
    }

    private void startDoseCountingFullscreen() {
        if (!ensurePermission()) return;
        recomputeTarget(); savePrefs();
        count = 0; lastClickNs = 0; targetReachedBuzzed = false; recomputeThreshold();
        mode = Mode.COUNTING;
        buildCounterUi(tr("set_dose"), targetClicks + " " + tr("clicks_target") + String.format(Locale.US, "%.2f mg", targetMg));
        startAudioIfNeeded();
        refreshUi();
    }

    private void startCalibrationFullscreen() {
        if (!ensurePermission()) return;
        count = 0; targetReachedBuzzed=false; clickCalibrationPeaks.clear(); clickCalibrationTimes.clear();
        calibrationStartNs=System.nanoTime(); lastClickNs=0;
        mode=Mode.CALIBRATING_NOISE;
        buildCounterUi(tr("calibrate"), tr("calibrate_subtitle"));
        startAudioIfNeeded();
        refreshUi();
    }

    private void startFullDoseMeasureFullscreen() {
        if (!ensurePermission()) return;
        count = 0; lastClickNs = 0; targetReachedBuzzed = false; recomputeThreshold();
        mode = Mode.MEASURE_FULL;
        buildCounterUi(tr("measure_full"), tr("measure_full_subtitle"));
        startAudioIfNeeded();
        refreshUi();
    }

    private void handleFullPrimaryPress() {
        if (mode == Mode.COUNTING && count >= targetClicks) { saveDoseAndSubtract(); buildHomeUi(); refreshUi(); return; }
        if (mode == Mode.MEASURE_FULL && count > 10) {
            fullClickDoses = count;
            remainingClicks = Math.min(remainingClicks, fullClickDoses * dosesPerPen);
            recomputeTarget();
            logEvent("MEASURE", fullClickDoses + " clicks · " + String.format(Locale.US, "%.2f mg", strengthMg));
            savePrefs();
            mode = Mode.IDLE; stopAudio(); buildHomeUi(); refreshUi(); return;
        }
        mode = Mode.IDLE; stopAudio(); buildHomeUi(); refreshUi();
    }

    private void showProfileDialog() {
        LinearLayout box = dialogBox(); box.addView(text(tr("profile_title"), 24, ink, true));
        EditText name = inputText(penName.isEmpty() ? tr("my_pen") : penName, tr("pen_name_hint"));
        EditText strength = input(String.valueOf(strengthMg), tr("full_dose_hint"));
        EditText doses = input(String.valueOf(dosesPerPen), tr("doses_per_pen"));
        EditText full = input(String.valueOf(fullClickDoses), tr("clicks_per_dose"));
        EditText rest = input(String.valueOf(remainingClicks), tr("remaining_hint"));
        TextView restWarning = text(tr("rest_change_warning"), 12, warn, false);
        TextView clicksHint2 = text(tr("pen_clicks_hint"), 11, muted, false);
        clicksHint2.setPadding(0, dp(2), 0, dp(6));
        box.addView(labelled(tr("pen_name_label"), name)); box.addView(labelled(tr("full_dose_label"), strength)); box.addView(labelled(tr("doses_per_pen"), doses)); box.addView(labelled(tr("clicks_per_dose"), full));
        box.addView(clicksHint2);
        box.addView(labelled(tr("current_rest"), rest)); box.addView(restWarning);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar).setView(box)
                .setPositiveButton(tr("save"), (d,w)->{ penName=name.getText().toString().trim(); strengthMg=readDouble(strength,strengthMg); dosesPerPen=Math.max(1,readInt(doses,dosesPerPen)); fullClickDoses=Math.max(1,readInt(full,fullClickDoses)); int max=fullClickDoses*dosesPerPen; remainingClicks=Math.max(0,Math.min(max,readInt(rest,remainingClicks))); recomputeTarget(); savePrefs(); refreshUi(); buildHomeUi(); refreshUi(); })
                .setNegativeButton(tr("cancel"), null).show();
    }

    private void showPlannerDialog() {
        int total = fullClickDoses * dosesPerPen;
        double mgPerClick = strengthMg / Math.max(1, fullClickDoses);

        LinearLayout box = dialogBox();
        box.addView(text(tr("planner_title"), 24, ink, true));
        box.addView(text(tr("planner_hint"), 13, muted, false));

        final double[] selectedMg = {-1};
        final TextView[] selectedRow = {null};
        final Button[] activateBtn = {null};

        ArrayList<int[]> plans = new ArrayList<>();
        for (int weeks = 4; weeks <= 12; weeks++) {
            int low = total / weeks;
            if (low < 1) break;
            plans.add(new int[]{weeks, low, total % weeks});
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(4));

        for (int[] p : plans) {
            int weeks = p[0]; int low = p[1]; int extra = p[2];
            double avgMg = (total / (double) weeks) * mgPerClick;
            boolean isCurrent = (Math.round(targetMg * 100) == Math.round(avgMg * 100));

            String label = extra == 0
                ? String.format(Locale.US, "%d " + tr("weeks") + " · %d " + tr("clicks_per_week") + " · %.2f mg", weeks, low, low * mgPerClick)
                : String.format(Locale.US, "%d " + tr("weeks") + " · %dx%d + %dx%d " + tr("clicks") + " · Ø %.2f mg", weeks, weeks - extra, low, extra, low + 1, avgMg);

            String rowText = (isCurrent ? "✓  " : "     ") + label;
            TextView row = text(rowText, 15, isCurrent ? Color.rgb(4,23,14) : ink, isCurrent);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(round(isCurrent ? accent : card2, 18));
            row.setTag(new Object[]{avgMg, label});

            final double rowMg = avgMg;
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (Math.round(targetMg * 100) == Math.round(avgMg * 100)) return;
                if (selectedRow[0] != null) selectedRow[0].setBackground(round(card2, 18));
                if (selectedRow[0] != null) selectedRow[0].setTextColor(ink);
                row.setBackground(round(blue, 18));
                row.setTextColor(Color.rgb(9, 9, 11));
                selectedRow[0] = row;
                selectedMg[0] = rowMg;
                if (activateBtn[0] != null) {
                    activateBtn[0].setEnabled(true);
                    activateBtn[0].setBackground(round(accent, 28));
                    activateBtn[0].setTextColor(Color.rgb(4, 23, 14));
                }
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dp(6), 0, 0);
            list.addView(row, rlp);
        }

        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(320));
        slp.setMargins(0, dp(10), 0, dp(10));
        box.addView(scroll, slp);

        Button activate = pillButton(tr("activate_plan"), track, muted);
        activate.setEnabled(false);
        activateBtn[0] = activate;
        box.addView(activate, new LinearLayout.LayoutParams(-1, dp(52)));

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setView(box)
            .setNegativeButton(tr("close"), null)
            .create();

        activate.setOnClickListener(v -> {
            if (selectedMg[0] < 0) return;
            double oldMg = targetMg;
            targetMg = selectedMg[0];
            recomputeTarget(); savePrefs(); refreshUi();
            logEvent("PLAN", String.format(Locale.US, "%.2f mg -> %.2f mg (%d clicks)", oldMg, targetMg, targetClicks));
            // Update alle Zeilen: Haken zum neuen Plan, alten entfernen
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (!(child instanceof TextView)) continue;
                TextView tv = (TextView) child;
                Object[] tag = (Object[]) tv.getTag();
                double rowMg = (Double) tag[0];
                String rowLabel = (String) tag[1];
                boolean isNow = (Math.round(targetMg * 100) == Math.round(rowMg * 100));
                tv.setBackground(round(isNow ? accent : card2, 18));
                tv.setTextColor(isNow ? Color.rgb(4, 23, 14) : ink);
                tv.setTypeface(null, isNow ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                tv.setText((isNow ? "✓  " : "     ") + rowLabel);
            }
            activate.setEnabled(false);
            activate.setBackground(round(track, 28));
            activate.setTextColor(muted);
            selectedMg[0] = -1;
            if (selectedRow[0] != null) selectedRow[0] = null;
        });

        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(-1, -2);
    }


    private void showHistoryDialog() {
        LinearLayout box = dialogBox();
        box.addView(text(tr("history_title"), 24, ink, true));
        TextView hint = text(tr("history_hint"), 12, muted, false);
        hint.setPadding(0, 0, 0, dp(4));
        box.addView(hint);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(8));

        if (injectionHistory == null || injectionHistory.trim().isEmpty()) {
            TextView empty = text(tr("history_empty"), 15, muted, false);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            empty.setGravity(Gravity.CENTER);
            list.addView(empty);
        } else {
            String[] rows = injectionHistory.split("\\n");
            int shown = 0;
            for (int i = rows.length - 1; i >= 0 && shown < 40; i--) {
                String row = rows[i].trim();
                if (row.isEmpty()) continue;
                String[] p = row.split("\\|");

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(6), 0, 0);

                // ── Event card (calibration, new pen, plan change) ──────────
                if (p.length >= 4 && "EVENT".equals(p[2])) {
                    String icon = "NEW_PEN".equals(p[3]) ? "💉" : "CALIB".equals(p[3]) ? "🎯" : "MEASURE".equals(p[3]) ? "📏" : "PEN_END".equals(p[3]) ? "🛑" : "↗";
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.HORIZONTAL);
                    card.setGravity(Gravity.CENTER_VERTICAL);
                    card.setPadding(dp(14), dp(10), dp(14), dp(10));
                    GradientDrawable border = new GradientDrawable();
                    border.setColor(bg); border.setCornerRadius(dp(16));
                    border.setStroke(dp(1), Color.rgb(45, 45, 50));
                    card.setBackground(border);
                    TextView iconTv = text(icon, 18, ink, false);
                    iconTv.setPadding(0, 0, dp(12), 0);
                    LinearLayout cardTexts = new LinearLayout(this);
                    cardTexts.setOrientation(LinearLayout.VERTICAL);
                    cardTexts.addView(text(tr("event_" + p[3].toLowerCase()), 13, muted, true));
                    if (p.length > 4) cardTexts.addView(text(p[4], 12, muted, false));
                    cardTexts.addView(text(p[0] + "  " + p[1], 11, Color.rgb(90,90,100), false));
                    card.addView(iconTv);
                    card.addView(cardTexts, new LinearLayout.LayoutParams(0, -2, 1));
                    list.addView(card, lp);

                // ── Dose card (new format: date|time|pen#|target|actual|mg|rest|total) ──
                } else if (p.length >= 7) {
                    LinearLayout card = roundedBox(card2, 16, dp(14));
                    // Top row: date + pen number
                    LinearLayout topRow = new LinearLayout(this);
                    topRow.setOrientation(LinearLayout.HORIZONTAL);
                    String penLabel = tr("pen_label") + " #" + p[2];
                    topRow.addView(text(p[0] + "  " + p[1], 12, muted, false));
                    TextView penLbl = text(penLabel, 12, muted, false);
                    penLbl.setGravity(Gravity.END);
                    topRow.addView(penLbl, new LinearLayout.LayoutParams(0, -2, 1));
                    card.addView(topRow, new LinearLayout.LayoutParams(-1, -2));
                    // Main: actual clicks
                    boolean offTarget = !p[3].equals(p[4]);
                    String clickLine = p[4] + " " + tr("clicks");
                    if (offTarget) clickLine += "  (" + tr("target") + ": " + p[3] + ")";
                    TextView clicksTv = text(clickLine, 22, ink, true);
                    clicksTv.setPadding(0, dp(4), 0, 0);
                    card.addView(clicksTv);
                    // Sub: mg + rest
                    card.addView(text("~" + p[5] + " mg  ·  " + tr("rest") + ": " + p[6] + " " + tr("clicks"), 13, muted, false));
                    list.addView(card, lp);

                // ── Legacy dose card (old format) ───────────────────────────
                } else if (p.length >= 5) {
                    LinearLayout card = roundedBox(card2, 16, dp(14));
                    card.addView(text(p[0] + "  " + p[1], 12, muted, false));
                    card.addView(text(p[2] + " " + tr("clicks"), 22, ink, true));
                    card.addView(text("~" + p[3] + " mg  ·  " + tr("rest") + ": " + p[4], 13, muted, false));
                    list.addView(card, lp);
                }
                shown++;
            }
        }
        scroll.addView(list);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(0, dp(6), 0, dp(4));
        scroll.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (scroll.getHeight() > dp(420)) { android.view.ViewGroup.LayoutParams lp2 = scroll.getLayoutParams(); lp2.height = dp(420); scroll.setLayoutParams(lp2); } return true; });
        box.addView(scroll, slp);

        AlertDialog[] dlgRef = {null};

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
        fLp.setMargins(0, dp(8), 0, dp(4));

        Button clear = pillButton(tr("clear_history"), Color.rgb(50,20,20), Color.rgb(200,80,80));
        clear.setTextSize(13);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setMessage(tr("clear_history_confirm"))
            .setPositiveButton(tr("clear_history"), (d, w) -> {
                injectionHistory = ""; savePrefs();
                if (dlgRef[0] != null) dlgRef[0].dismiss();
                Toast.makeText(this, tr("history_cleared"), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(tr("cancel"), null).show());

        Button close = pillButton(tr("close"), track, muted);
        close.setOnClickListener(v -> { if (dlgRef[0] != null) dlgRef[0].dismiss(); });

        footer.addView(clear, new LinearLayout.LayoutParams(0, dp(46), 1));
        footer.addView(new Space(this), new LinearLayout.LayoutParams(dp(10), 1));
        footer.addView(close, new LinearLayout.LayoutParams(0, dp(46), 1));
        box.addView(footer, fLp);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setView(box).create();
        dlgRef[0] = dlg;
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(-1, -2);
    }

    private void showLanguageDialog() {
        final String[] opts = {"Deutsch", "English"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
                .setTitle("Language / Sprache")
                .setSingleChoiceItems(opts, language.equals("en") ? 1 : 0, (d, which) -> {
                    language = which == 1 ? "en" : "de";
                    savePrefs();
                    d.dismiss();
                    buildHomeUi();
                    refreshUi();
                })
                .setNegativeButton(tr("cancel"), null)
                .show();
    }

    private void confirmNewPen() {
        // Step 1: ask keep or reduce
        LinearLayout box = dialogBox();
        box.addView(text(tr("new_pen"), 24, ink, true));
        box.addView(text(tr("new_pen_plan_question"), 14, muted, false));

        // Current plan info
        double mgPerClick = strengthMg / Math.max(1, fullClickDoses);
        // Reduce by 15%, then optimise for zero remainder within ±5
        int reducedClicks = Math.max(targetPlanClicks, (int) Math.round(currentPlanClicks * 0.85));
        int total = fullClickDoses * dosesPerPen;
        if (reducedClicks > 0 && total % reducedClicks != 0) {
            for (int delta = 1; delta <= 5; delta++) {
                int lo = reducedClicks - delta, hi = reducedClicks + delta;
                if (lo >= targetPlanClicks && lo > 0 && total % lo == 0) { reducedClicks = lo; break; }
                if (hi < currentPlanClicks && total % hi == 0) { reducedClicks = hi; break; }
            }
        }
        final int finalReduced = Math.max(targetPlanClicks, Math.max(1, reducedClicks));

        // Option cards
        LinearLayout keepCard = optionCard(
            "✓  " + tr("plan_keep"),
            String.format(Locale.US, "%d " + tr("clicks") + "  ·  ~%.2f mg", currentPlanClicks, currentPlanClicks * mgPerClick),
            accent);
        LinearLayout reduceCard = optionCard(
            "↓  " + tr("plan_reduce"),
            String.format(Locale.US, "%d " + tr("clicks") + "  ·  ~%.2f mg", finalReduced, finalReduced * mgPerClick),
            blue);

        final boolean[] chooseReduce = {false};
        keepCard.setOnClickListener(v -> { chooseReduce[0] = false; keepCard.setBackground(round(accent, 18)); keepCard.getChildAt(0).setAlpha(1f); reduceCard.setBackground(round(card2, 18)); });
        reduceCard.setOnClickListener(v -> { chooseReduce[0] = true; reduceCard.setBackground(round(blue, 18)); keepCard.setBackground(round(card2, 18)); });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        box.addView(keepCard, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(-1, -2);
        lp2.setMargins(0, dp(8), 0, 0);
        box.addView(reduceCard, lp2);

        AlertDialog[] dlgRef = {null};
        Button confirm = pillButton(tr("new_pen_start"), accent, Color.rgb(4,23,14));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.setMargins(0, dp(18), 0, dp(4));
        confirm.setOnClickListener(v -> {
            if (dlgRef[0] != null) dlgRef[0].dismiss();
            penNumber++;
            remainingClicks = fullClickDoses * dosesPerPen;
            if (chooseReduce[0]) {
                currentPlanClicks = finalReduced;
                logEvent("NEW_PEN", "Pen #" + penNumber + " · reduce → " + currentPlanClicks + " clicks");
            } else {
                logEvent("NEW_PEN", "Pen #" + penNumber + " · keep " + currentPlanClicks + " clicks");
            }
            targetClicks = currentPlanClicks;
            targetMg = targetClicks * mgPerClick;
            planActive = true;
            savePrefs(); buildHomeUi(); refreshUi();
            // Optional: measure new pen
            new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
                .setMessage(tr("new_pen_measure_prompt"))
                .setPositiveButton(tr("measure_now"), (d2, w2) -> startFullDoseMeasureFullscreen())
                .setNegativeButton(tr("skip_for_now"), null)
                .show();
        });
        box.addView(confirm, blp);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setView(box).create();
        dlgRef[0] = dlg;
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(-1, -2);
    }

    private LinearLayout optionCard(String title, String subtitle, int borderColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setClickable(true); card.setFocusable(true);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(card2); bd.setCornerRadius(dp(18));
        bd.setStroke(dp(2), borderColor);
        card.setBackground(bd);
        card.addView(text(title, 16, ink, true));
        card.addView(text(subtitle, 13, muted, false));
        return card;
    }

    private void markPenEmpty() {
        int expected = remainingClicks;
        if (expected <= 0) {
            // Already empty — go straight to new pen
            confirmNewPen(); return;
        }
        String msg = String.format(tr("pen_stop_msg"), expected);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setTitle(tr("pen_reached_stop"))
            .setMessage(msg)
            .setPositiveButton(tr("pen_confirm_empty"), (d, w) -> {
                int discrepancy = expected;
                logEvent("PEN_END", "Pen #" + penNumber + " · expected " + expected + " remaining · adjusted to 0");
                remainingClicks = 0;
                savePrefs(); refreshUi();
                // Show calm confirmation then offer new pen
                new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
                    .setMessage(String.format(tr("pen_completed_msg"), discrepancy))
                    .setPositiveButton(tr("new_pen"), (d2, w2) -> confirmNewPen())
                    .setNegativeButton(tr("close"), null)
                    .show();
            })
            .setNegativeButton(tr("cancel"), null)
            .show();
    }

    private void showMoreDialog() {
        LinearLayout box = dialogBox();
        box.addView(text(tr("more_title"), 24, ink, true));
        TextView sub = text(tr("more_subtitle"), 13, muted, false);
        sub.setPadding(0, 0, 0, dp(8));
        box.addView(sub);

        AlertDialog[] dlgRef = {null};

        String[][] items = {
            {"🛑", tr("pen_reached_stop"),  tr("pen_reached_stop_desc")},
            {"💉", tr("new_pen"),            tr("more_newpen_desc")},
            {"📏", tr("measure_full"),       tr("more_measure_desc")},
            {"🌐", "Language / Sprache",     tr("more_lang_desc")},
        };
        Runnable[] actions = {
            () -> { dlgRef[0].dismiss(); markPenEmpty(); },
            () -> { dlgRef[0].dismiss(); confirmNewPen(); },
            () -> { dlgRef[0].dismiss(); startFullDoseMeasureFullscreen(); },
            () -> { dlgRef[0].dismiss(); showLanguageDialog(); },
        };

        for (int i = 0; i < items.length; i++) {
            final Runnable action = actions[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(round(card2, 20));
            row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> action.run());

            TextView icon = text(items[i][0], 22, ink, false);
            icon.setPadding(0, 0, dp(14), 0);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(text(items[i][1], 16, ink, true));
            texts.addView(text(items[i][2], 12, muted, false));
            row.addView(icon);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, 0);
            box.addView(row, lp);
        }

        Button close = pillButton(tr("close"), track, muted);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, dp(48));
        clp.setMargins(0, dp(18), 0, dp(4));
        box.addView(close, clp);

        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_Material_NoActionBar)
            .setView(box).create();
        dlgRef[0] = dlg;
        close.setOnClickListener(v -> dlg.dismiss());
        if (dlg.getWindow() != null) dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(-1, -2);
    }

    private void refreshUi() {
        int total = Math.max(1, fullClickDoses * dosesPerPen);
        double mgPerClick = strengthMg / Math.max(1, fullClickDoses);
        double remMg = remainingClicks * mgPerClick;

        if (centerMainView != null) {
            boolean penEmpty = remainingClicks <= 0;
            boolean penNearlyEmpty = !penEmpty && remainingClicks < targetClicks;

            // Dominant: target clicks
            centerMainView.setText(String.valueOf(targetClicks));
            centerSubView.setText(tr("clicks"));
            centerTinyView.setText(String.format(Locale.US, "~%.2f mg", targetMg));

            // Ring: pen remaining
            penRing.progress = Math.max(0f, Math.min(1f, remainingClicks / (float) total));
            penRing.segments = Math.min(120, Math.max(20, total / 3));
            penRing.progressColor = penEmpty ? warn : accent;
            penRing.invalidate();

            // Stat cards: remaining + doses left
            leftStatView.setText(String.format(Locale.US, tr("rest_stat_fmt"), remainingClicks, total));
            int dosesLeft = targetClicks > 0 ? remainingClicks / targetClicks : 0;
            int remD = targetClicks > 0 ? remainingClicks % targetClicks : 0;
            rightStatView.setText(tr("doses_label") + "\n" + (penEmpty ? "0" : remD == 0 ? dosesLeft : "~" + dosesLeft));
            rightStatView.setTextColor(penEmpty || penNearlyEmpty ? warn : ink);

            if (lastDoseView != null) lastDoseView.setText(lastDoseText());

            // Primary button
            if (penEmpty) {
                primaryButton.setText(tr("new_pen_cta"));
                primaryButton.setBackground(round(warn, 30));
                primaryButton.setTextColor(Color.rgb(30, 15, 0));
                primaryButton.setOnClickListener(v -> confirmNewPen());
            } else {
                primaryButton.setText(tr("start_counting"));
                primaryButton.setBackground(round(accent, 30));
                primaryButton.setTextColor(Color.rgb(4, 23, 14));
                primaryButton.setOnClickListener(v -> startDoseCountingFullscreen());
            }
        }

        if (fullRing != null) {
            if (mode == Mode.COUNTING) {
                fullCountView.setText(String.valueOf(count));
                fullSubView.setText("/ " + targetClicks);
                if (count < targetClicks) fullHintView.setText((targetClicks-count) + " " + tr("clicks_to_goal"));
                else if (count == targetClicks) fullHintView.setText(tr("goal_reached"));
                else fullHintView.setText(tr("over_goal"));
                fullRing.segments = Math.max(8, Math.min(96, targetClicks));
                fullRing.progress = Math.max(0f, Math.min(1.15f, count/(float)Math.max(1,targetClicks)));
                fullRing.progressColor = count>targetClicks ? warn : accent;
                fullPrimaryButton.setText(count>=targetClicks ? tr("save_dose") : tr("stop_counting"));
                fullPrimaryButton.setBackground(round(count>=targetClicks ? accent : card2, 28));
                fullPrimaryButton.setTextColor(count>=targetClicks ? Color.rgb(4,23,14) : ink);
            } else if (mode == Mode.MEASURE_FULL) {
                fullCountView.setText(String.valueOf(count));
                fullSubView.setText(tr("clicks"));
                fullHintView.setText(tr("save_at_stop"));
                fullRing.segments = Math.max(8, Math.min(96, fullClickDoses));
                fullRing.progress = Math.max(0f, Math.min(1.15f, count/(float)Math.max(1, fullClickDoses)));
                fullRing.progressColor = blue;
                fullPrimaryButton.setText(count>10 ? tr("save_full_dose") : tr("stop_counting"));
            } else if (mode == Mode.CALIBRATING_NOISE || mode == Mode.CALIBRATING_CLICKS) {
                fullCountView.setText(String.valueOf(count));
                fullSubView.setText("/ 10");
                fullHintView.setText(mode==Mode.CALIBRATING_NOISE ? tr("calib_noise_hint") : tr("calib_click_hint"));
                fullRing.segments = 10;
                fullRing.progress = Math.max(0f, Math.min(1f, count/10f));
                fullRing.progressColor = blue;
                fullPrimaryButton.setText(tr("cancel"));
            }
            boolean isCalib = mode == Mode.CALIBRATING_NOISE || mode == Mode.CALIBRATING_CLICKS;
            fullMinusButton.setVisibility(isCalib ? View.INVISIBLE : View.VISIBLE);
            fullPlusButton.setVisibility(isCalib ? View.INVISIBLE : View.VISIBLE);
            fullRing.invalidate();
        }
    }

    private void saveCountInProgress() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("countInProgress", mode == Mode.COUNTING ? count : -1).apply();
    }

    private void saveDoseAndSubtract() {
        if (count <= 0) return;
        int used = count;
        double mg = used * strengthMg / Math.max(1, fullClickDoses);
        int restAfter = Math.max(0, remainingClicks - used);
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd|HH:mm", Locale.getDefault());
        String stamp = df.format(new java.util.Date());
        // Format: date|time|pen#|targetClicks|actualClicks|mg|restAfter|totalClicks
        injectionHistory = (injectionHistory == null ? "" : injectionHistory)
            + stamp
            + "|" + penNumber
            + "|" + targetClicks
            + "|" + used
            + "|" + String.format(Locale.US, "%.2f", mg)
            + "|" + restAfter
            + "|" + (fullClickDoses * dosesPerPen)
            + "\n";
        remainingClicks = restAfter;
        count = 0; targetReachedBuzzed = false; mode = Mode.IDLE; stopAudio(); savePrefs();
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("countInProgress", -1).apply();
    }

    private void logEvent(String type, String detail) {
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd|HH:mm", Locale.getDefault());
        String stamp = df.format(new java.util.Date());
        injectionHistory = (injectionHistory == null ? "" : injectionHistory) + stamp + "|EVENT|" + type + "|" + detail + "\n";
        savePrefs();
    }

    private String lastDoseText() {
        if (injectionHistory == null || injectionHistory.trim().isEmpty()) return tr("no_dose_yet");
        String[] rows = injectionHistory.split("\\n");
        for (int i = rows.length - 1; i >= 0; i--) {
            String row = rows[i].trim();
            if (row.isEmpty()) continue;
            String[] p = row.split("\\|");
            if (p.length >= 4 && "EVENT".equals(p[2])) continue;
            // New format: date|time|pen#|targetClicks|actualClicks|mg|restAfter|total
            if (p.length >= 6) {
                return tr("last_dose_fmt") + "  " + p[0] + " · " + p[4] + " " + tr("clicks") + " · ~" + p[5] + " mg";
            }
            // Legacy format: date|time|clicks|mg|...
            if (p.length >= 4) {
                return tr("last_dose_fmt") + "  " + p[0] + " · " + p[2] + " " + tr("clicks") + " · ~" + p[3] + " mg";
            }
        }
        return tr("no_dose_yet");
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, tr("mic_granted"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, tr("mic_denied"), Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean ensurePermission(){ if(Build.VERSION.SDK_INT>=23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO); return false;} return true; }
    private void startAudioIfNeeded(){ if(audioActive)return; audioActive=true; audioThread=new Thread(this::audioLoop,"ClickPlanAudio"); audioThread.start(); }
    private void stopAudio(){ audioActive=false; uiHandler.removeCallbacks(measureDoneRunnable); }
    private void onMeasurePause(){ if(mode==Mode.MEASURE_FULL&&count>0&&fullPrimaryButton!=null){ fullPrimaryButton.setBackground(round(accent,28)); fullPrimaryButton.setTextColor(Color.rgb(4,23,14)); fullPrimaryButton.setText(tr("done")); } }
    private void recomputeTarget(){ targetClicks=(int)Math.round(fullClickDoses*targetMg/Math.max(0.001,strengthMg)); if(targetClicks<1)targetClicks=1; }
    private void recomputeThreshold(){ threshold=Math.max(calibratedClickPeak*thresholdFactor, noiseAvgJump*9.0+450.0); }
    private double median(ArrayList<Double> v){ if(v.isEmpty())return 0; ArrayList<Double> c=new ArrayList<>(v); Collections.sort(c); return c.get(c.size()/2); }

    private void finishCalibration(){
        if(!clickCalibrationPeaks.isEmpty()) calibratedClickPeak=median(clickCalibrationPeaks);
        if(clickCalibrationTimes.size()>=2){ ArrayList<Double> gaps=new ArrayList<>(); for(int i=1;i<clickCalibrationTimes.size();i++)gaps.add((clickCalibrationTimes.get(i)-clickCalibrationTimes.get(i-1))/1_000_000.0); double gap=median(gaps); cooldownMs=(int)Math.max(55,Math.min(130,gap*0.45)); } else cooldownMs=80;
        recomputeThreshold(); mode=Mode.IDLE;
        logEvent("CALIB", String.format(Locale.US, "%d clicks · peak %.0f · cooldown %d ms", clickCalibrationPeaks.size(), calibratedClickPeak, cooldownMs));
        savePrefs();
        runOnUiThread(() -> { buildHomeUi(); refreshUi(); Toast.makeText(this, String.format(tr("calibrated_toast"), clickCalibrationPeaks.size()), Toast.LENGTH_SHORT).show(); });
    }

    private void audioLoop(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        int min=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize=Math.max(min,512);
        int source=Build.VERSION.SDK_INT>=24?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;
        AudioRecord rec=null;
        try{
            rec=new AudioRecord(source,SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize*4);
            short[] buf=new short[bufferSize]; int prev=0; rec.startRecording();
            while(audioActive){
                int n=rec.read(buf,0,buf.length); if(n<=0)continue; double peak=0,sum=0;
                for(int i=0;i<n;i++){ int x=buf[i]; double jump=Math.abs(x-prev); prev=x; if(jump>peak)peak=jump; sum+=jump; }
                latestPeak=peak; latestAvgJump=sum/Math.max(1,n); long now=System.nanoTime();
                if(mode==Mode.CALIBRATING_NOISE){ noiseAvgJump=noiseAvgJump*0.92+latestAvgJump*0.08; if(now-calibrationStartNs>2_000_000_000L){ calibrationStartNs=now; lastClickNs=0; mode=Mode.CALIBRATING_CLICKS; runOnUiThread(this::refreshUi); }}
                else if(mode==Mode.CALIBRATING_CLICKS){ double rough=Math.max(noiseAvgJump*12.0+600.0,900.0); if(now-lastClickNs>65_000_000L && peak>rough){ lastClickNs=now; clickCalibrationPeaks.add(peak); clickCalibrationTimes.add(now); count=clickCalibrationPeaks.size(); runOnUiThread(this::refreshUi);} if(now-calibrationStartNs>6_000_000_000L) finishCalibration(); }
                else if(mode==Mode.COUNTING||mode==Mode.MEASURE_FULL){ if(peak<threshold*0.55){ noiseAvgJump=noiseAvgJump*0.995+latestAvgJump*0.005; recomputeThreshold(); } if(now-lastClickNs>cooldownMs*1_000_000L && peak>threshold){ lastClickNs=now; count++; if(mode==Mode.MEASURE_FULL){ uiHandler.removeCallbacks(measureDoneRunnable); uiHandler.postDelayed(measureDoneRunnable, 3500); } runOnUiThread(() -> { checkTargetFeedback(); refreshUi(); saveCountInProgress(); }); }}
            }
        } catch(Throwable t){ runOnUiThread(() -> Toast.makeText(this, "Audio-Fehler: "+t.getMessage(), Toast.LENGTH_LONG).show()); }
        finally{ try{ if(rec!=null) rec.stop(); }catch(Exception ignored){} try{ if(rec!=null) rec.release(); }catch(Exception ignored){} audioActive=false; }
    }

    private void checkTargetFeedback(){ if(mode==Mode.COUNTING&&!targetReachedBuzzed&&count>=targetClicks){ targetReachedBuzzed=true; vibrate(count==targetClicks?180:420); } }
    private void vibrate(int ms){ try{ Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE); if(v==null)return; if(Build.VERSION.SDK_INT>=26)v.vibrate(VibrationEffect.createOneShot(ms,VibrationEffect.DEFAULT_AMPLITUDE)); else v.vibrate(ms);}catch(Exception ignored){} }

    private TextView text(String s,int sp,int color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); t.setPadding(0,dp(3),0,dp(3)); return t; }
    private TextView statCard(String s){ TextView t=text(s,14,ink,true); t.setGravity(Gravity.CENTER); t.setBackground(round(card2,22)); return t; }
    private LinearLayout roundedBox(int color,int radius,int pad){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(pad,pad,pad,pad); l.setBackground(round(color,radius)); return l; }
    private GradientDrawable round(int color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private Button ghostButton(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(ink); b.setBackground(round(Color.TRANSPARENT,22)); b.setPadding(0,0,0,0); return b; }
    private Button smallRoundButton(String s){ Button b=ghostButton(s); b.setTextSize(21); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(round(card2,30)); return b; }
    private Button pillButton(String s,int bgc,int tc){ Button b=new Button(this); b.setText(s); b.setTextColor(tc); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setBackground(round(bgc,30)); return b; }
    private LinearLayout dialogBox(){ LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(22),dp(18),dp(22),dp(8)); b.setBackgroundColor(bg); return b; }
    private LinearLayout labelled(String label, EditText input){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); TextView tv=text(label,12,muted,true); l.addView(tv); l.addView(input); return l; }
    private EditText input(String value,String hint){ EditText e=new EditText(this); e.setText(value); e.setHint(hint); e.setTextColor(ink); e.setHintTextColor(muted); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL); return e; }
    private EditText inputText(String value,String hint){ EditText e=new EditText(this); e.setText(value); e.setHint(hint); e.setTextColor(ink); e.setHintTextColor(muted); e.setSingleLine(true); e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); return e; }
    private int bottomInsetPx(){
        if (Build.VERSION.SDK_INT >= 23) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                }
                return insets.getStableInsetBottom();
            }
        }
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }
    private int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

    private String tr(String key) {
        boolean en = "en".equals(language);
        if (en) {
            switch (key) {
                case "calibrate": return "Calibrate";
                case "target": return "Target";
                case "history": return "History";
                case "more": return "More";
                case "planner": return "Planner";
                case "clicks": return "clicks";
                case "rest": return "Rest";
                case "history_title": return "Injection log";
                case "history_hint": return "Stored locally. Clicks are the source of truth.";
                case "history_empty": return "No saved doses yet.";
                case "clear_history": return "Clear history";
                case "history_cleared": return "History cleared";
                case "cancel": return "Cancel";
                case "more_title": return "More";
                case "measure_full": return "Measure full-dose clicks";
                case "new_pen": return "New full pen";
                case "current_pen": return "CURRENT PEN";
                case "live_count": return "counting live";
                case "ready": return "Ready";
                case "set_dose": return "Set dose";
                case "clicks_target": return "clicks target ·";
                case "calibrate_subtitle": return "hold still first, then 5–10 clicks";
                case "measure_full_subtitle": return "turn pen to stop";
                case "target_title": return "Daily target";
                case "target_hint": return "The active plan is stored locally.";
                case "target_dose_label": return "Target dose mg";
                case "apply": return "Apply";
                case "profile_title": return "Pen profile";
                case "full_dose_label": return "Full dose";
                case "full_dose_hint": return "Full dose mg";
                case "doses_per_pen": return "Doses per pen";
                case "clicks_per_dose": return "Clicks per full dose";
                case "remaining_hint": return "Remaining clicks";
                case "current_rest": return "Current remaining";
                case "save": return "Save";
                case "planner_title": return "Dose split plans";
                case "planner_hint": return "Remainder-free splits calculated from your pen. Not a medical recommendation.";
                case "weeks": return "weeks";
                case "clicks_per_week": return "clicks/week";
                case "stop_counting": return "Stop counting";
                case "save_dose": return "Save dose";
                case "clicks_to_goal": return "clicks to go";
                case "goal_reached": return "Goal reached";
                case "over_goal": return "over goal · use −1 to correct";
                case "save_at_stop": return "save when at stop";
                case "save_full_dose": return "Save as full dose";
                case "hold_still": return "hold still …";
                case "turn_clicks": return "turn clicks now …";
                case "turn_to_goal": return "Turn until goal is reached";
                case "calibrated_toast": return "Calibrated: %d clicks";
                case "clicks_remaining_fmt": return "clicks remaining · ~%.2f mg";
                case "today_mg_fmt": return "today · %.2f mg";
                case "rest_stat_fmt": return "Rest\n%d of %d";
                case "today_stat_fmt": return "Today\n%d clicks";
                case "set_clicks_fmt": return "Set %d clicks";
                case "new_pen_measure_prompt": return "Do you want to measure the full-dose clicks now? This ensures the plan has no remainder.";
                case "measure_now": return "Measure now";
                case "use_saved": return "Use saved value";
                case "new_pen_set_toast": return "New pen set: %d clicks";
                case "pen_name_label": return "Pen name";
                case "pen_name_hint": return "e.g. My Pen";
                case "my_pen": return "My Pen";
                case "plan_confirm_title": return "Activate plan?";
                case "plan_confirm_msg": return "Set target to %d clicks · %.2f mg per dose?\nThis takes effect from the next dose.";
                case "activate_plan": return "Activate";
                case "plan_activated_toast": return "Plan activated: %d clicks per dose";
                case "rest_change_warning": return "⚠ Only change this if you are correcting an error.";
                case "plan": return "Plan";
                case "close": return "Close";
                case "onboarding_title": return "Welcome to ClickPlan";
                case "onboarding_msg": return "Let's set up your pen first so the plan fits perfectly.";
                case "setup_pen": return "Set up pen →";
                case "profile_setup_hint": return "Enter your pen's full dose and click count.";
                case "next_choose_plan": return "Next: choose plan →";
                case "done": return "Done";
                case "event_plan": return "Plan changed";
                case "event_new_pen": return "New pen";
                case "event_calib": return "Calibrated";
                case "event_measure": return "Full dose measured";
                case "clear_history_confirm": return "Delete the entire history? This cannot be undone.";
                case "weeks_left_exact": return "%d doses remaining";
                case "weeks_left_approx": return "~%d doses remaining";
                case "doses_label": return "Doses";
                case "onboarding_disclaimer": return "⚠ Not a medical device. Always verify your dose visually before injecting.";
                case "calibrate_prompt_title": return "Calibrate first?";
                case "calibrate_prompt_msg": return "Calibration helps the app reliably detect your pen's clicks. Recommended before first use.";
                case "skip_for_now": return "Skip for now";
                case "new_pen_cta": return "New pen →";
                case "more_subtitle": return "Settings & tools";
                case "more_measure_desc": return "Count clicks on a full pen to calibrate the total";
                case "more_newpen_desc": return "Reset clicks for a new pen, optionally re-measure";
                case "more_lang_desc": return "Switch between German and English";
                case "tap_to_count": return "tap button to count";
                case "last_dose_fmt": return "Last dose";
                case "no_dose_yet": return "No doses recorded yet";
                case "mic_granted": return "Microphone access granted";
                case "mic_denied": return "Microphone access denied — click counting needs the mic";
                case "pen_clicks_hint": return "Reference: Wegovy/Ozempic 0.25–1.0 mg → 20 clicks · 1.7 mg → 57 · 2.4 mg → 74";
                case "calib_noise_hint": return "Hold pen still — measuring background noise …";
                case "calib_click_hint": return "Now slowly turn your pen and count 5–10 clicks";
                case "clicks_remaining_label": return "CLICKS REMAINING";
                case "onboarding_rings": return "The green ring shows how much of your pen is left. The number in the centre is your dose for this week.";
                case "this_week": return "THIS WEEK";
                case "start_counting": return "Start counting →";
                case "pen_label": return "Pen";
                case "new_pen_plan_question": return "How do you want to continue?";
                case "plan_keep": return "Keep current dose";
                case "plan_reduce": return "Reduce one step";
                case "new_pen_start": return "Start new pen →";
                case "pen_reached_stop": return "Pen reached stop";
                case "pen_reached_stop_desc": return "Mark pen as empty if it stopped before expected";
                case "pen_stop_msg": return "ClickPlan expected %d clicks remaining. If your pen has physically stopped, confirm to set remaining clicks to 0.";
                case "pen_confirm_empty": return "Pen is empty";
                case "pen_completed_msg": return "Pen completed. ClickPlan adjusted %d remaining clicks to 0. This is normal — pens vary slightly.";
                case "event_pen_end": return "Pen completed";
            }
        } else {
            switch (key) {
                case "calibrate": return "Kalibrieren";
                case "target": return "Ziel";
                case "history": return "Protokoll";
                case "more": return "Mehr";
                case "planner": return "Planner";
                case "clicks": return "Klicks";
                case "rest": return "Rest";
                case "history_title": return "Protokoll";
                case "history_hint": return "Lokal gespeichert. Klicks sind die Wahrheit.";
                case "history_empty": return "Noch keine gespeicherte Dosis.";
                case "clear_history": return "Protokoll löschen";
                case "history_cleared": return "Protokoll gelöscht";
                case "cancel": return "Abbrechen";
                case "more_title": return "Mehr";
                case "measure_full": return "Volldosis-Klicks messen";
                case "new_pen": return "Neuer voller Pen";
                case "current_pen": return "AKTUELLER PEN";
                case "live_count": return "wird live gezählt";
                case "ready": return "Bereit";
                case "set_dose": return "Dose einstellen";
                case "clicks_target": return "Klicks Ziel ·";
                case "calibrate_subtitle": return "erst still halten, dann 5–10 Klicks";
                case "measure_full_subtitle": return "Pen bis Anschlag drehen";
                case "target_title": return "Tagesziel";
                case "target_hint": return "Der aktive Plan ist lokal gespeichert.";
                case "target_dose_label": return "Zieldosis mg";
                case "apply": return "Übernehmen";
                case "profile_title": return "Pen-Profil";
                case "full_dose_label": return "Volldosis";
                case "full_dose_hint": return "Volldosis mg";
                case "doses_per_pen": return "Dosen pro Pen";
                case "clicks_per_dose": return "Klicks pro Volldosis";
                case "remaining_hint": return "Restklicks im Pen";
                case "current_rest": return "Aktueller Rest";
                case "save": return "Speichern";
                case "planner_title": return "Restfrei-Vorschläge";
                case "planner_hint": return "Rechnerisch, keine medizinische Empfehlung.";
                case "weeks": return "Wochen";
                case "clicks_per_week": return "Klicks/Woche";
                case "stop_counting": return "Zählen stoppen";
                case "save_dose": return "Dosis speichern";
                case "clicks_to_goal": return "Klicks bis Ziel";
                case "goal_reached": return "Ziel erreicht";
                case "over_goal": return "über Ziel · mit −1 korrigieren";
                case "save_at_stop": return "bei Anschlag speichern";
                case "save_full_dose": return "Als Volldosis speichern";
                case "hold_still": return "still halten …";
                case "turn_clicks": return "jetzt Klicks drehen …";
                case "turn_to_goal": return "Drehen bis Ziel erreicht ist";
                case "calibrated_toast": return "Kalibriert: %d Klicks";
                case "clicks_remaining_fmt": return "Klicks übrig · ca. %.2f mg";
                case "today_mg_fmt": return "heute · %.2f mg";
                case "rest_stat_fmt": return "Rest\n%d von %d";
                case "today_stat_fmt": return "Heute\n%d Klicks";
                case "set_clicks_fmt": return "%d Klicks einstellen";
                case "new_pen_measure_prompt": return "Möchtest du die Volldosis-Klicks jetzt messen? So stellt der Plan sicher, dass kein Rest bleibt.";
                case "measure_now": return "Jetzt messen";
                case "use_saved": return "Gespeicherten Wert nutzen";
                case "new_pen_set_toast": return "Neuer Pen gesetzt: %d Klicks";
                case "pen_name_label": return "Pen-Name";
                case "pen_name_hint": return "z.B. Mein Pen";
                case "my_pen": return "Mein Pen";
                case "plan_confirm_title": return "Plan aktivieren?";
                case "plan_confirm_msg": return "Ziel auf %d Klicks · %.2f mg pro Dosis setzen?\nGilt ab der nächsten Dosis.";
                case "activate_plan": return "Aktivieren";
                case "plan_activated_toast": return "Plan aktiviert: %d Klicks pro Dosis";
                case "rest_change_warning": return "⚠ Nur ändern wenn du einen Fehler korrigierst.";
                case "plan": return "Plan";
                case "close": return "Schliessen";
                case "onboarding_title": return "Willkommen bei ClickPlan";
                case "onboarding_msg": return "Richte zuerst deinen Pen ein, damit der Plan perfekt passt.";
                case "setup_pen": return "Pen einrichten →";
                case "profile_setup_hint": return "Gib die Volldosis und Klickzahl deines Pens ein.";
                case "next_choose_plan": return "Weiter: Plan wählen →";
                case "done": return "Fertig";
                case "event_plan": return "Plan geändert";
                case "event_new_pen": return "Neuer Pen";
                case "event_calib": return "Kalibriert";
                case "event_measure": return "Volldosis gemessen";
                case "clear_history_confirm": return "Das gesamte Protokoll löschen? Das kann nicht rückgängig gemacht werden.";
                case "weeks_left_exact": return "%d Dosen verbleibend";
                case "weeks_left_approx": return "~%d Dosen verbleibend";
                case "doses_label": return "Dosen";
                case "onboarding_disclaimer": return "⚠ Kein Medizinprodukt. Überprüfe die Dosis immer visuell bevor du injizierst.";
                case "calibrate_prompt_title": return "Erst kalibrieren?";
                case "calibrate_prompt_msg": return "Die Kalibrierung hilft der App, die Klicks deines Pens zuverlässig zu erkennen. Empfohlen vor dem ersten Gebrauch.";
                case "skip_for_now": return "Jetzt überspringen";
                case "new_pen_cta": return "Neuer Pen →";
                case "more_subtitle": return "Einstellungen & Werkzeuge";
                case "more_measure_desc": return "Klicks eines vollen Pens zählen um die Gesamtzahl zu prüfen";
                case "more_newpen_desc": return "Klicks für neuen Pen zurücksetzen, optional neu messen";
                case "more_lang_desc": return "Zwischen Deutsch und Englisch wechseln";
                case "tap_to_count": return "Taste drücken zum Zählen";
                case "last_dose_fmt": return "Letzte Dosis";
                case "no_dose_yet": return "Noch keine Dosis gespeichert";
                case "mic_granted": return "Mikrofon-Zugriff erlaubt";
                case "mic_denied": return "Mikrofon-Zugriff verweigert — Klickerkennung braucht das Mikrofon";
                case "pen_clicks_hint": return "Referenz: Wegovy/Ozempic 0.25–1.0 mg → 20 Klicks · 1.7 mg → 57 · 2.4 mg → 74";
                case "calib_noise_hint": return "Pen ruhig halten — Hintergrundgeräusche werden gemessen …";
                case "calib_click_hint": return "Jetzt langsam drehen und 5–10 Klicks zählen";
                case "clicks_remaining_label": return "KLICKS VERBLEIBEND";
                case "onboarding_rings": return "Der grüne Ring zeigt wie viel noch im Pen ist. Die Zahl in der Mitte ist deine Dosis für diese Woche.";
                case "this_week": return "DIESE WOCHE";
                case "start_counting": return "Zählen starten →";
                case "pen_label": return "Pen";
                case "new_pen_plan_question": return "Wie möchtest du weitermachen?";
                case "plan_keep": return "Dosis beibehalten";
                case "plan_reduce": return "Einen Schritt reduzieren";
                case "new_pen_start": return "Neuen Pen starten →";
                case "pen_reached_stop": return "Pen am Anschlag";
                case "pen_reached_stop_desc": return "Pen als leer markieren, wenn er vor dem erwarteten Ende stoppt";
                case "pen_stop_msg": return "ClickPlan erwartete noch %d Klicks. Falls dein Pen physisch gestoppt hat, bestätige um den Rest auf 0 zu setzen.";
                case "pen_confirm_empty": return "Pen ist leer";
                case "pen_completed_msg": return "Pen abgeschlossen. ClickPlan hat %d verbleibende Klicks auf 0 angepasst. Das ist normal — Pens variieren leicht.";
                case "event_pen_end": return "Pen abgeschlossen";
            }
        }
        return key;
    }

    private double readDouble(EditText e,double f){ try{return Double.parseDouble(e.getText().toString().replace(',','.'));}catch(Exception x){return f;} }
    private int readInt(EditText e,int f){ try{return (int)Math.round(Double.parseDouble(e.getText().toString().replace(',','.')));}catch(Exception x){return f;} }
    private void loadPrefs(){ SharedPreferences p=getSharedPreferences(PREF,MODE_PRIVATE); drugName=p.getString("drugName",""); strengthMg=Double.longBitsToDouble(p.getLong("strength",Double.doubleToLongBits(2.4))); dosesPerPen=p.getInt("doses",4); fullClickDoses=p.getInt("fullClicks",74); remainingClicks=p.getInt("remaining",fullClickDoses*dosesPerPen); targetMg=Double.longBitsToDouble(p.getLong("target",Double.doubleToLongBits(1.2))); targetClicks=p.getInt("targetClicks",37); penNumber=p.getInt("penNumber",1); currentPlanClicks=p.getInt("currentPlanClicks",targetClicks); targetPlanClicks=p.getInt("targetPlanClicks",1); planActive=p.getBoolean("planActive",false); thresholdFactor=Double.longBitsToDouble(p.getLong("factor",Double.doubleToLongBits(0.38))); noiseAvgJump=Double.longBitsToDouble(p.getLong("noise",Double.doubleToLongBits(180.0))); calibratedClickPeak=Double.longBitsToDouble(p.getLong("clickPeak",Double.doubleToLongBits(2600.0))); cooldownMs=p.getInt("cooldown",80); language=p.getString("language","en"); penName=p.getString("penName",""); injectionHistory=p.getString("history",""); }
    private void savePrefs(){ getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("drugName",drugName).putLong("strength",Double.doubleToLongBits(strengthMg)).putInt("doses",dosesPerPen).putInt("fullClicks",fullClickDoses).putInt("remaining",remainingClicks).putLong("target",Double.doubleToLongBits(targetMg)).putInt("targetClicks",targetClicks).putInt("penNumber",penNumber).putInt("currentPlanClicks",currentPlanClicks).putInt("targetPlanClicks",targetPlanClicks).putBoolean("planActive",planActive).putLong("factor",Double.doubleToLongBits(thresholdFactor)).putLong("noise",Double.doubleToLongBits(noiseAvgJump)).putLong("clickPeak",Double.doubleToLongBits(calibratedClickPeak)).putInt("cooldown",cooldownMs).putString("language",language).putString("penName",penName).putString("history",injectionHistory).apply(); }
    @Override protected void onDestroy(){ audioActive=false; super.onDestroy(); }

    public class SegmentedRingView extends View {
        public float progress = 0.75f; public int segments = 74; public int strokeDp = 10; public int trackColor = Color.DKGRAY; public int progressColor = accent;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        public SegmentedRingView(Context c){ super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            int w=getWidth(), h=getHeight();
            float stroke=dp(strokeDp);
            p.setStrokeWidth(stroke);

            // Keep the ring perfectly circular on devices such as the Galaxy S21.
            // The view may be laid out as a rectangle, so drawing must use a centered square.
            float size = Math.min(w, h);
            float left = (w - size) / 2f;
            float top = (h - size) / 2f;
            float pad = stroke / 2f + dp(5);
            RectF r = new RectF(left + pad, top + pad, left + size - pad, top + size - pad);

            float gap=Math.max(1.5f, 360f/Math.max(segments,1)*0.22f);
            float sweep=360f/Math.max(segments,1)-gap;
            int active=Math.round(Math.min(1f,Math.max(0f,progress))*segments);
            for(int i=0;i<segments;i++){
                p.setColor(i<active?progressColor:trackColor);
                p.setAlpha(i<active?255:112);
                c.drawArc(r,-90+i*(360f/segments)+gap/2,sweep,false,p);
            }
            p.setAlpha(255);
        }
    }
}
