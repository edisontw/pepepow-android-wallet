package com.tbuonomo.viewpagerdotsindicator;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.viewpager.widget.ViewPager;

/**
 * Lightweight placeholder implementation of the third-party dots indicator.
 * This keeps the existing layout structure compiling while the upstream dependency
 * is migrated for PEPEPOW.
 */
public class DotsIndicator extends View {

    public DotsIndicator(Context context) {
        super(context);
    }

    public DotsIndicator(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DotsIndicator(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setViewPager(@Nullable ViewPager viewPager) {
        // no-op placeholder; real indicator behaviour will be restored in a future PEPEPOW update.
    }
}
