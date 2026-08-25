package com.libredex.transport.vnc;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.connect_screen.mirror.R;

/**
 * Simple control panel rendered inside the optional transport container.
 * Built in code so the transport module needs no layout XML; strings come
 * from the merged app resources (no hardcoded text per the i18n gate).
 */
public final class VncControlFragment extends Fragment {

    private static final int DEFAULT_PORT = 5900;

    private TextView status;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 32, 48, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        status = new TextView(requireContext());
        status.setTextColor(Color.BLACK);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setText(buildStatus());

        Button start = new Button(requireContext());
        start.setText(getString(R.string.vnc_start));
        start.setOnClickListener(v -> {
            VncTransportProvider.INSTANCE.restart(true, null);
            refresh();
        });

        Button stop = new Button(requireContext());
        stop.setText(getString(R.string.vnc_stop));
        stop.setOnClickListener(v -> {
            VncTransportProvider.INSTANCE.stop();
            refresh();
        });

        root.addView(status);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.topMargin = 24;
        start.setLayoutParams(btnParams);
        stop.setLayoutParams(btnParams);
        root.addView(start);
        root.addView(stop);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (status != null) {
            status.setText(buildStatus());
        }
    }

    private String buildStatus() {
        if (VncTransportProvider.INSTANCE.isActive()) {
            return getString(R.string.vnc_status_running,
                    DEFAULT_PORT, VncTransportProvider.INSTANCE.activeDisplayId());
        }
        return getString(R.string.vnc_status_not_running, DEFAULT_PORT);
    }
}
