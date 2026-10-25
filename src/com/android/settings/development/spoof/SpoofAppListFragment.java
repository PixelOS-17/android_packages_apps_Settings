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

import android.app.settings.SettingsEnums;
import android.content.ContentResolver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.security.gameprops.GamePropsSpoofService;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.util.custom.PixelDeviceRepository;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.development.DeveloperOptionAwareMixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Package picker for TrickyStore targets, Tensor targets, Pixel-prop targets,
 * and per-app device profiles. Each mode writes the setting format its service
 * parses.
 */
public abstract class SpoofAppListFragment extends SettingsPreferenceFragment
        implements DeveloperOptionAwareMixin {

    enum Mode { TRICKY, TENSOR, PIXEL, APP }

    private static final String[] EXCLUDED_SUFFIXES = {
            ".auto_generated", ".appsearch", ".backup", ".carrier",
            ".cellbroadcast", ".cts", ".federated", ".ims", ".overlay",
            ".qti", ".qualcomm", ".resources", ".systemui.clocks",
            ".systemui.plugin", ".theme", ".iconpack",
    };
    private static final String[] TRICKY_MODES = {"", "?", "!", "-"};

    protected abstract Mode mode();

    protected abstract int titleRes();

    private final List<Row> mRows = new ArrayList<>();
    private PreferenceCategory mApps;
    private boolean mShowSystem;
    private String mQuery = "";
    private boolean mLoaded;
    private boolean mAppSpoofEnabled = true;

    static final class Row {
        final String pkg;
        final String label;
        final boolean system;
        boolean selected;
        String extra;

        Row(String pkg, String label, boolean system, boolean selected, String extra) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.selected = selected;
            this.extra = extra == null ? "" : extra;
        }
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.DEVELOPMENT;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
        onBuildHeader(screen);

        androidx.preference.SwitchPreferenceCompat showSystem =
                new androidx.preference.SwitchPreferenceCompat(requireContext());
        showSystem.setKey("spoof_show_system");
        showSystem.setTitle(R.string.spoof_show_system);
        showSystem.setPersistent(false);
        showSystem.setOnPreferenceChangeListener((p, value) -> {
            mShowSystem = Boolean.TRUE.equals(value);
            fill();
            return true;
        });
        screen.addPreference(showSystem);

        EditTextPreference filter = new EditTextPreference(requireContext());
        filter.setKey("spoof_filter");
        filter.setTitle(R.string.spoof_filter);
        filter.setPersistent(false);
        filter.setOnPreferenceChangeListener((p, value) -> {
            mQuery = value == null ? "" : String.valueOf(value).trim();
            filter.setSummary(mQuery);
            fill();
            return true;
        });
        screen.addPreference(filter);

        mApps = new PreferenceCategory(requireContext());
        mApps.setKey("spoof_apps");
        mApps.setTitle(titleRes());
        screen.addPreference(mApps);
    }

    protected void onBuildHeader(PreferenceScreen screen) {}

    protected void onRefresh() {}

    /** Per-app spoof keeps its selection in the cache while the master switch is off. */
    protected void setAppSpoofEnabled(boolean enabled) {
        ContentResolver cr = requireContext().getContentResolver();
        String active = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF);
        String cache = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE);
        Set<String> stop = new HashSet<>();
        stop.addAll(SpoofStore.readPairMap(active).keySet());
        stop.addAll(SpoofStore.readPairMap(cache).keySet());
        if (enabled) {
            if (TextUtils.isEmpty(active)) {
                SpoofStore.putString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF,
                        cache == null ? "" : cache);
            }
        } else {
            SpoofStore.putString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE,
                    active == null ? "" : active);
            SpoofStore.putString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF, "");
        }
        SpoofStore.putInt(cr, false, Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED, enabled ? 1 : 0);
        mAppSpoofEnabled = enabled;
        SpoofStore.stopPackages(requireContext(), stop.toArray(new String[0]));
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) getActivity().setTitle(titleRes());
        onRefresh();
        if (!mLoaded) load();
    }

    private void load() {
        mLoaded = true;
        ContentResolver cr = requireContext().getContentResolver();
        seedIfUnset(cr);
        if (mode() == Mode.APP) {
            mAppSpoofEnabled = SpoofStore.getInt(cr, false,
                    Settings.Secure.PER_APPS_DEVICE_SPOOF_ENABLED, 1) != 0;
        }
        PackageManager pm = requireContext().getPackageManager();
        Set<String> hidden = mode() == Mode.TENSOR
                ? new HashSet<>(java.util.Arrays.asList(getResources().getStringArray(
                        R.array.tensor_targets_hidden_apps)))
                : Collections.emptySet();
        final boolean appEnabled = mAppSpoofEnabled;
        new Thread(() -> {
            List<Row> rows = query(pm, cr, hidden, appEnabled);
            android.app.Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                mRows.clear();
                mRows.addAll(rows);
                fill();
            });
        }, "spoof-apps").start();
    }

    private void seedIfUnset(ContentResolver cr) {
        if (mode() == Mode.TRICKY) {
            if (SpoofStore.getString(cr, Settings.Secure.SPOOF_TRICKYSTORE_TARGET) == null) {
                SpoofStore.putString(cr, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                        TrickyStoreService.DEFAULT_TARGET_LIST);
            }
        } else if (mode() == Mode.TENSOR) {
            if (SpoofStore.getString(cr, SpoofStore.TENSOR_TARGETS) == null) {
                SpoofStore.putString(cr, SpoofStore.TENSOR_TARGETS, SpoofStore.writeCsv(
                        java.util.Arrays.asList(getResources().getStringArray(
                                R.array.tensor_targets_default_apps))));
            }
        }
    }

    private List<Row> query(PackageManager pm, ContentResolver cr, Set<String> hidden,
            boolean appEnabled) {
        Set<String> selected = new HashSet<>();
        Map<String, String> extras = Collections.emptyMap();
        switch (mode()) {
            case TRICKY:
                extras = SpoofStore.readTargetMap(
                        SpoofStore.getString(cr, Settings.Secure.SPOOF_TRICKYSTORE_TARGET));
                selected.addAll(extras.keySet());
                break;
            case TENSOR:
                selected.addAll(SpoofStore.readCsv(
                        SpoofStore.getString(cr, SpoofStore.TENSOR_TARGETS)));
                break;
            case PIXEL:
                List<String> stored = SpoofStore.readCsv(
                        SpoofStore.getString(cr, SpoofStore.PP_TARGETS));
                if (stored.isEmpty()) {
                    selected.addAll(PixelDeviceRepository.DEFAULT_PP_TARGETS);
                } else {
                    selected.addAll(stored);
                }
                break;
            case APP:
                String active = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF);
                String cache = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE);
                Map<String, String> shown = SpoofStore.readPairMap(appEnabled ? active : cache);
                if (appEnabled && shown.isEmpty()) {
                    shown = SpoofStore.readPairMap(cache);
                }
                extras = shown;
                selected.addAll(shown.keySet());
                break;
            default:
                break;
        }
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<Row> rows = new ArrayList<>();
        for (ApplicationInfo info : installed) {
            if (info.packageName == null) continue;
            boolean isSystem = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isSelected = selected.contains(info.packageName);
            if (mode() == Mode.TRICKY
                    && TrickyStoreService.XPOSED_PACKAGES.contains(info.packageName)) {
                continue;
            }
            if (hidden.contains(info.packageName) && !isSelected) continue;
            if (isSystem && !isSelected && excludedSuffix(info.packageName)) continue;
            String label;
            try {
                label = pm.getApplicationLabel(info).toString();
            } catch (RuntimeException e) {
                label = info.packageName;
            }
            rows.add(new Row(info.packageName, label, isSystem, isSelected,
                    extras.getOrDefault(info.packageName, "")));
        }
        rows.sort((a, b) -> {
            if (a.selected != b.selected) return a.selected ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        return rows;
    }

    private static boolean excludedSuffix(String pkg) {
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (pkg.contains(suffix)) return true;
        }
        return false;
    }

    private void fill() {
        if (mApps == null) return;
        mApps.removeAll();
        String query = mQuery.toLowerCase(Locale.US);
        boolean any = false;
        for (Row row : mRows) {
            if (!row.selected && row.system && !mShowSystem) continue;
            if (!query.isEmpty()
                    && !row.label.toLowerCase(Locale.US).contains(query)
                    && !row.pkg.toLowerCase(Locale.US).contains(query)) {
                continue;
            }
            any = true;
            mApps.addPreference(rowPreference(row));
        }
        if (!any) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.spoof_no_apps);
            empty.setSelectable(false);
            empty.setPersistent(false);
            mApps.addPreference(empty);
        }
    }

    private Preference rowPreference(Row row) {
        if (mode() == Mode.TENSOR || mode() == Mode.PIXEL) {
            CheckBoxPreference pref = new CheckBoxPreference(requireContext());
            pref.setKey("pkg:" + row.pkg);
            pref.setTitle(row.label);
            pref.setSummary(row.pkg);
            pref.setPersistent(false);
            pref.setChecked(row.selected);
            pref.setOnPreferenceChangeListener((p, value) -> {
                row.selected = Boolean.TRUE.equals(value);
                save();
                SpoofStore.stopPackages(requireContext(), row.pkg);
                return true;
            });
            return pref;
        }
        Preference pref = new Preference(requireContext());
        pref.setKey("pkg:" + row.pkg);
        pref.setTitle(row.label);
        pref.setSummary(summaryFor(row));
        pref.setPersistent(false);
        pref.setOnPreferenceClickListener(p -> {
            if (mode() == Mode.TRICKY) showModeDialog(row, pref);
            else showProfileDialog(row, pref);
            return true;
        });
        return pref;
    }

    private String summaryFor(Row row) {
        if (mode() == Mode.TRICKY) {
            if (!row.selected) return getString(R.string.ts_mode_off);
            if ("?".equals(row.extra)) return getString(R.string.ts_mode_leaf);
            if ("!".equals(row.extra)) return getString(R.string.ts_mode_cert);
            if ("-".equals(row.extra)) return getString(R.string.ts_mode_skip);
            return getString(R.string.ts_mode_auto);
        }
        if (!row.selected || row.extra.isEmpty()) return getString(R.string.spoof_profile_off);
        return profileLabel(row.extra);
    }

    private void showModeDialog(Row row, Preference pref) {
        String[] labels = {
                getString(R.string.ts_mode_off),
                getString(R.string.ts_mode_auto),
                getString(R.string.ts_mode_leaf),
                getString(R.string.ts_mode_cert),
                getString(R.string.ts_mode_skip),
        };
        int checked = 0;
        if (row.selected) {
            for (int i = 0; i < TRICKY_MODES.length; i++) {
                if (TRICKY_MODES[i].equals(row.extra)) checked = i + 1;
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ts_choose_mode)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which == 0) {
                        row.selected = false;
                        row.extra = "";
                    } else {
                        row.selected = true;
                        row.extra = TRICKY_MODES[which - 1];
                    }
                    pref.setSummary(summaryFor(row));
                    save();
                    SpoofStore.stopPackages(requireContext(), row.pkg);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showProfileDialog(Row row, Preference pref) {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add("");
        labels.add(getString(R.string.spoof_profile_off));
        List<String> builtin = new ArrayList<>(GamePropsSpoofService.BUILTIN_PROFILES.keySet());
        Collections.sort(builtin);
        for (String id : builtin) {
            ids.add(id);
            labels.add(profileLabel(id));
        }
        for (SpoofStore.CustomProfile profile : SpoofStore.readCustomProfiles(
                requireContext().getContentResolver())) {
            ids.add(profile.id);
            labels.add(profile.name);
        }
        int checked = 0;
        if (row.selected) {
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equals(row.extra)) checked = i;
            }
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.spoof_choose_profile)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    if (which == 0) {
                        row.selected = false;
                        row.extra = "";
                    } else {
                        row.selected = true;
                        row.extra = ids.get(which);
                    }
                    pref.setSummary(summaryFor(row));
                    save();
                    SpoofStore.stopPackages(requireContext(), row.pkg);
                    d.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String profileLabel(String id) {
        Map<String, Object> props = GamePropsSpoofService.BUILTIN_PROFILES.get(id);
        if (props != null && props.get("MODEL") != null) {
            return String.valueOf(props.get("MODEL"));
        }
        for (SpoofStore.CustomProfile profile : SpoofStore.readCustomProfiles(
                requireContext().getContentResolver())) {
            if (id.equals(profile.id)) return profile.name;
        }
        return id;
    }

    private void save() {
        ContentResolver cr = requireContext().getContentResolver();
        Set<String> visible = new HashSet<>();
        for (Row row : mRows) visible.add(row.pkg);
        switch (mode()) {
            case TRICKY: {
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : SpoofStore.readTargetMap(
                        SpoofStore.getString(cr, Settings.Secure.SPOOF_TRICKYSTORE_TARGET))
                        .entrySet()) {
                    if (!visible.contains(entry.getKey())) {
                        out.put(entry.getKey(), entry.getValue());
                    }
                }
                for (Row row : mRows) {
                    if (row.selected) out.put(row.pkg, row.extra);
                }
                SpoofStore.putString(cr, Settings.Secure.SPOOF_TRICKYSTORE_TARGET,
                        SpoofStore.writeTargetMap(out));
                break;
            }
            case TENSOR:
            case PIXEL: {
                String key = mode() == Mode.TENSOR
                        ? SpoofStore.TENSOR_TARGETS : SpoofStore.PP_TARGETS;
                List<String> stored = SpoofStore.readCsv(SpoofStore.getString(cr, key));
                // An empty Pixel-prop list means the runtime default set. A save has
                // to keep the defaults that are not installed, or the first edit
                // replaces that set with only the packages on screen.
                if (mode() == Mode.PIXEL && stored.isEmpty()) {
                    stored = new ArrayList<>(PixelDeviceRepository.DEFAULT_PP_TARGETS);
                }
                List<String> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String pkg : stored) {
                    if (!visible.contains(pkg) && seen.add(pkg)) out.add(pkg);
                }
                for (Row row : mRows) {
                    if (row.selected && seen.add(row.pkg)) out.add(row.pkg);
                }
                SpoofStore.putString(cr, key, SpoofStore.writeCsv(out));
                break;
            }
            case APP: {
                String active = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF);
                String cache = SpoofStore.getString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE);
                Map<String, String> shown = SpoofStore.readPairMap(mAppSpoofEnabled ? active : cache);
                if (mAppSpoofEnabled && shown.isEmpty()) {
                    shown = SpoofStore.readPairMap(cache);
                }
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : shown.entrySet()) {
                    if (!visible.contains(entry.getKey())) {
                        out.put(entry.getKey(), entry.getValue());
                    }
                }
                for (Row row : mRows) {
                    if (row.selected && !row.extra.isEmpty()) out.put(row.pkg, row.extra);
                }
                String encoded = SpoofStore.writePairMap(out);
                SpoofStore.putString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF_CACHE, encoded);
                SpoofStore.putString(cr, Settings.Secure.PER_APPS_DEVICE_SPOOF,
                        mAppSpoofEnabled ? encoded : "");
                break;
            }
            default:
                break;
        }
    }
}
