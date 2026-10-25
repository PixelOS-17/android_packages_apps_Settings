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

import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settings.R;

public class TensorTargetsFragment extends SpoofAppListFragment {
    @Override
    protected Mode mode() {
        return Mode.TENSOR;
    }

    @Override
    protected int titleRes() {
        return R.string.tensor_spoof_title;
    }

    @Override
    protected void onBuildHeader(PreferenceScreen screen) {
        SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(requireContext());
        enabled.setKey("pi_tensor_spoof");
        enabled.setTitle(R.string.tensor_enable);
        enabled.setSummary(R.string.tensor_spoof_summary);
        enabled.setPersistent(false);
        enabled.setChecked(SpoofStore.getInt(requireContext().getContentResolver(), false,
                Settings.Secure.PI_TENSOR_SPOOF, 0) != 0);
        enabled.setOnPreferenceChangeListener((p, value) -> {
            android.content.ContentResolver cr = requireContext().getContentResolver();
            SpoofStore.putInt(cr, false, Settings.Secure.PI_TENSOR_SPOOF,
                    Boolean.TRUE.equals(value) ? 1 : 0);
            String raw = SpoofStore.getString(cr, SpoofStore.TENSOR_TARGETS);
            java.util.List<String> pkgs = raw == null
                    ? java.util.Arrays.asList(getResources().getStringArray(
                            R.array.tensor_targets_default_apps))
                    : SpoofStore.readCsv(raw);
            SpoofStore.stopPackages(requireContext(), pkgs.toArray(new String[0]));
            return true;
        });
        screen.addPreference(enabled);
    }
}
