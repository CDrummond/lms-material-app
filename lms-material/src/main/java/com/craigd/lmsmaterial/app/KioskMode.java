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

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Optional per-installation "kiosk mode". When enabled, the app's settings can
 * only be opened after entering a PIN. This only protects against accidental or
 * casual changes; it is not a replacement for Android device policies.
 */
public class KioskMode {
    public static final String PIN_PREF_KEY = "kiosk_pin"; // "<salt>:<hash>", unset when disabled

    public interface Callback {
        void onSuccess();
    }

    public static boolean isEnabled(SharedPreferences prefs) {
        String stored = prefs.getString(PIN_PREF_KEY, null);
        return stored != null && !stored.isEmpty();
    }

    public static void disable(SharedPreferences prefs) {
        prefs.edit().remove(PIN_PREF_KEY).apply();
    }

    public static void setPin(SharedPreferences prefs, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String saltHex = toHex(salt);
        prefs.edit().putString(PIN_PREF_KEY, saltHex + ":" + hash(saltHex, pin)).apply();
    }

    public static boolean checkPin(SharedPreferences prefs, String pin) {
        String stored = prefs.getString(PIN_PREF_KEY, null);
        if (stored == null) {
            return true;
        }
        int sep = stored.indexOf(':');
        if (sep < 1) {
            return false;
        }
        return MessageDigest.isEqual(stored.substring(sep + 1).getBytes(), hash(stored.substring(0, sep), pin).getBytes());
    }

    /** Ask for the current PIN; calls back only if it is correct. */
    public static void askPin(Context context, SharedPreferences prefs, Callback callback) {
        final EditText input = pinInput(context);
        new AlertDialog.Builder(context)
                .setTitle(R.string.kiosk_enter_pin)
                .setView(wrap(context, input))
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    if (checkPin(prefs, input.getText().toString())) {
                        callback.onSuccess();
                    } else {
                        toast(context, R.string.kiosk_wrong_pin);
                    }
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel())
                .show();
    }

    /** Ask for a new PIN (twice) and store it. */
    public static void askNewPin(Context context, SharedPreferences prefs, Callback callback) {
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
                        setPin(prefs, p1);
                        callback.onSuccess();
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

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest((salt + pin).getBytes("UTF-8")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
