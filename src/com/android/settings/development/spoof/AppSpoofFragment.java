/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.development.spoof;

import android.provider.Settings;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class AppSpoofFragment extends SpoofAppListFragment {
    private PreferenceCategory mCustom;

    @Override
    protected Mode mode() {
        return Mode.APP;
    }

    @Override
    protected int titleRes() {
        return R.string.user_selectable_app_spoofing_title;
    }

    @Override
    protected void onBuildHeader(PreferenceScreen screen) {
        SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(requireContext());
        enabled.setKey("app_spoof_enable");
        enabled.setTitle(R.string.app_spoof_enable);
        enabled.setSummary(R.string.user_selectable_app_spoofing_summary);
        enabled.setPersistent(false);
        enabled.setChecked(SpoofStore.getInt(requireContext().getContentResolver(), false,
                Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED, 1) != 0);
        enabled.setOnPreferenceChangeListener((p, value) -> {
            setAppSpoofEnabled(Boolean.TRUE.equals(value));
            return true;
        });
        screen.addPreference(enabled);

        Preference add = new Preference(requireContext());
        add.setKey("spoof_add_profile");
        add.setTitle(R.string.spoof_add_profile);
        add.setPersistent(false);
        add.setOnPreferenceClickListener(p -> {
            showAddProfile();
            return true;
        });
        screen.addPreference(add);

        mCustom = new PreferenceCategory(requireContext());
        mCustom.setKey("spoof_custom_profiles");
        mCustom.setTitle(R.string.spoof_custom_profiles);
        screen.addPreference(mCustom);
    }

    @Override
    protected void onRefresh() {
        if (mCustom == null || !isAdded()) return;
        mCustom.removeAll();
        for (SpoofStore.CustomProfile profile : SpoofStore.readCustomProfiles(
                requireContext().getContentResolver())) {
            Preference row = new Preference(requireContext());
            row.setKey("custom:" + profile.id);
            row.setTitle(profile.name);
            row.setSummary(profile.model);
            row.setPersistent(false);
            row.setOnPreferenceClickListener(p -> {
                confirmDelete(profile);
                return true;
            });
            mCustom.addPreference(row);
        }
    }

    private void showAddProfile() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, pad);
        EditText name = field(form, R.string.custom_spoof_profile_name);
        EditText brand = field(form, R.string.custom_spoof_profile_brand);
        EditText manufacturer = field(form, R.string.custom_spoof_profile_manufacturer);
        EditText device = field(form, R.string.custom_spoof_profile_device);
        EditText model = field(form, R.string.custom_spoof_profile_model);
        EditText fingerprint = field(form, R.string.custom_spoof_profile_fingerprint);
        EditText product = field(form, R.string.custom_spoof_profile_product);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(form);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.spoof_add_profile)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> saveProfile(
                        text(name), text(brand), text(manufacturer), text(device), text(model),
                        text(fingerprint), text(product)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText field(LinearLayout form, int hint) {
        EditText input = new EditText(requireContext());
        input.setHint(hint);
        input.setSingleLine(true);
        form.addView(input);
        return input;
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private void saveProfile(String name, String brand, String manufacturer, String device,
            String model, String fingerprint, String product) {
        if (name.isEmpty() || brand.isEmpty() || manufacturer.isEmpty()
                || device.isEmpty() || model.isEmpty()) {
            Toast.makeText(requireContext(), R.string.spoof_profile_incomplete,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            List<SpoofStore.CustomProfile> profiles = new ArrayList<>(
                    SpoofStore.readCustomProfiles(requireContext().getContentResolver()));
            profiles.add(new SpoofStore.CustomProfile(
                    "c" + System.currentTimeMillis(), name, brand, manufacturer, device, model,
                    fingerprint, product));
            SpoofStore.writeCustomProfiles(requireContext().getContentResolver(), profiles);
            Toast.makeText(requireContext(), R.string.spoof_profile_saved,
                    Toast.LENGTH_SHORT).show();
            onRefresh();
        } catch (JSONException e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(SpoofStore.CustomProfile profile) {
        new AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.spoof_delete_profile, profile.name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<SpoofStore.CustomProfile> kept = new ArrayList<>();
                    for (SpoofStore.CustomProfile existing : SpoofStore.readCustomProfiles(
                            requireContext().getContentResolver())) {
                        if (!existing.id.equals(profile.id)) kept.add(existing);
                    }
                    try {
                        SpoofStore.writeCustomProfiles(requireContext().getContentResolver(), kept);
                        onRefresh();
                    } catch (JSONException e) {
                        Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
