/*
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import io.github.muddz.styleabletoast.StyleableToast;

/**
 * Optional per-installation "kiosk mode". When enabled, the app's settings can
 * only be opened after entering a PIN. This only protects against accidental or
 * casual changes; it is not a replacement for Android device policies.
 */
public class KioskMode {
    // Stored like the LMS password: plain text in private, non-backed-up prefs
    public static final String PIN_PREF_KEY = "kiosk_pin";

    public static boolean isEnabled(SharedPreferences prefs) {
        return !prefs.getString(PIN_PREF_KEY, "").isEmpty();
    }

    public static void disable(SharedPreferences prefs) {
        prefs.edit().remove(PIN_PREF_KEY).apply();
    }

    /** Ask for the current PIN; calls back only if it is correct. */
    public static void askPin(Context context, SharedPreferences prefs, Runnable callback) {
        final EditText input = pinInput(context);
        new AlertDialog.Builder(context)
                .setTitle(R.string.kiosk_enter_pin)
                .setView(wrap(context, input))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (input.getText().toString().equals(prefs.getString(PIN_PREF_KEY, ""))) {
                        callback.run();
                    } else {
                        toast(context, R.string.kiosk_wrong_pin);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    /** Ask for a new PIN (twice) and store it. */
    public static void askNewPin(Context context, SharedPreferences prefs, Runnable callback) {
        final EditText pin1 = pinInput(context);
        final EditText pin2 = pinInput(context);
        pin2.setHint(R.string.kiosk_repeat_pin);
        LinearLayout layout = wrap(context, pin1);
        layout.addView(pin2);
        new AlertDialog.Builder(context)
                .setTitle(R.string.kiosk_set_pin)
                .setView(layout)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String p1 = pin1.getText().toString();
                    if (p1.length() < 4) {
                        toast(context, R.string.kiosk_pin_too_short);
                    } else if (!p1.equals(pin2.getText().toString())) {
                        toast(context, R.string.kiosk_pin_mismatch);
                    } else {
                        prefs.edit().putString(PIN_PREF_KEY, p1).apply();
                        callback.run();
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    private static void toast(Context context, int resId) {
        StyleableToast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT, R.style.toast).show();
    }

    private static EditText pinInput(Context context) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return input;
    }

    private static LinearLayout wrap(Context context, EditText input) {
        int padding = context.getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding / 2);
        layout.addView(input);
        return layout;
    }

}
