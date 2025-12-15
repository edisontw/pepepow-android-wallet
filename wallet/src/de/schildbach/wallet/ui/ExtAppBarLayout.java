package de.schildbach.wallet.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import com.google.android.material.appbar.AppBarLayout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;

import org.pepepow.wallet.R;

/**
 * @author Tomasz Ludek
 */
public class ExtAppBarLayout extends AppBarLayout {

    public ExtAppBarLayout(Context context) {
        super(context);
        init();
    }

    public ExtAppBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        initView();
    }

    private void initView() {
        inflate(getContext(), R.layout.ext_app_bar_layout, this);
        inflate(getContext(), R.layout.ext_app_bar_bottom_layout, this);
        setBackgroundColor(Color.TRANSPARENT);
    }
}
