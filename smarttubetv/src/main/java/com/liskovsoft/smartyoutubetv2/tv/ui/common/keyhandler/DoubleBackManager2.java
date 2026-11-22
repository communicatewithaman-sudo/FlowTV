package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.content.Context;

public class DoubleBackManager2 {
    private static final String TAG = DoubleBackManager2.class.getSimpleName();
    private static final int DEFAULT_REPEAT_COUNT = 2;
    private final Context mContext;
    private int mRepeatCount;
    private long mMsgShownTimeMs;

    public DoubleBackManager2(Context ctx) {
        mContext = ctx;
    }

    public void enableDoubleBackExit(Runnable onDoubleBack) {
        // Reset if the user didn't do second press within interval
        if (System.currentTimeMillis() - mMsgShownTimeMs > 5_000) {
            resetBackPressed();
        }

        mRepeatCount++;

        // same event fires multiple times
        boolean isDoubleBackPressed = mRepeatCount >= DEFAULT_REPEAT_COUNT;

        // YouTok: No toast shown here — tips are shown inside the menu instead
        if (mRepeatCount == (DEFAULT_REPEAT_COUNT - 1)) {
            mMsgShownTimeMs = System.currentTimeMillis();
        }

        if (isDoubleBackPressed) {
            onDoubleBack.run();
        }
    }

    private void resetBackPressed() {
        mRepeatCount = 0;
    }
}
