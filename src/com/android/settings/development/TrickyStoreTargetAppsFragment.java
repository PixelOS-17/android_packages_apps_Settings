/*
 * Copyright (C) 2026 AxionOS
 * Copyright (C) 2026 VoltageOS
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
package com.android.settings.development;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.security.trickystore.TrickyStoreService;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.SearchView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settings.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses which packages receive keybox attestation and which mode each one uses.
 * The list is Settings.Secure.SPOOF_TRICKYSTORE_TARGET, in the same text syntax the
 * framework parses: "pkg" auto, "pkg?" leaf hack, "pkg!" certificate generation,
 * "pkg-" skip. An empty setting means the framework default list, so that list is
 * what this screen shows until the user changes it. Clearing every package writes
 * a comment-only value so the framework does not put the defaults back.
 */
public class TrickyStoreTargetAppsFragment extends Fragment {

    private static final String TAG = "TrickyStoreTargets";
    private static final String NONE_SELECTED = "#";

    public enum TargetMode {
        AUTO(""),
        LEAF_HACK("?"),
        CERT_GEN("!");

        public final String symbol;

        TargetMode(String symbol) {
            this.symbol = symbol;
        }
    }

    private final Map<String, String> mTargets = new LinkedHashMap<>();
    private final List<AppEntry> mAllApps = new ArrayList<>();
    private final List<AppEntry> mFilteredApps = new ArrayList<>();

    private AppAdapter mAdapter;
    private TextView mSelectedCount;
    private boolean mShowSystem;
    private boolean mLoaded;
    private boolean mNeedsRestart;
    private String mSearchQuery = "";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        requireActivity().setTitle(R.string.trickystore_target_screen_title);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.trickystore_target_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recyclerView = view.findViewById(R.id.trickystore_target_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new AppAdapter();
        recyclerView.setAdapter(mAdapter);
        mSelectedCount = view.findViewById(R.id.trickystore_target_count);
        loadApps();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().setTitle(R.string.trickystore_target_screen_title);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.trickystore_target_apps, menu);

        MenuItem searchItem = menu.findItem(R.id.trickystore_target_search);
        if (searchItem != null) {
            SearchView searchView = (SearchView) searchItem.getActionView();
            if (searchView != null) {
                searchView.setQueryHint(getString(R.string.app_list_search_apps));
                searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                    @Override
                    public boolean onQueryTextSubmit(String query) {
                        return false;
                    }

                    @Override
                    public boolean onQueryTextChange(String newText) {
                        mSearchQuery = newText != null ? newText.trim() : "";
                        filterApps();
                        return true;
                    }
                });

                if (!mSearchQuery.isEmpty()) {
                    searchItem.expandActionView();
                    searchView.setQuery(mSearchQuery, false);
                    searchView.clearFocus();
                }
            }
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        MenuItem showSystem = menu.findItem(R.id.trickystore_target_show_system);
        if (showSystem != null) {
            showSystem.setChecked(mShowSystem);
        }
        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.trickystore_target_show_system) {
            mShowSystem = !mShowSystem;
            item.setChecked(mShowSystem);
            filterApps();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (persistSelection()) {
            mNeedsRestart = true;
        }
        if (mNeedsRestart) {
            forceStopClients();
            mNeedsRestart = false;
        }
    }

    private void loadApps() {
        final Context context = getContext();
        if (context == null) {
            return;
        }

        new Thread(() -> {
            PackageManager packageManager = context.getPackageManager();
            String stored = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
            Map<String, String> targets = parseTargets(TextUtils.isEmpty(stored)
                    ? TrickyStoreService.DEFAULT_TARGET_LIST : stored);
            List<ApplicationInfo> installedApps =
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppEntry> entries = new ArrayList<>();
            Set<String> installedPackages = new HashSet<>();

            for (ApplicationInfo applicationInfo : installedApps) {
                installedPackages.add(applicationInfo.packageName);
                AppEntry entry = new AppEntry();
                entry.packageName = applicationInfo.packageName;
                entry.label = packageManager.getApplicationLabel(applicationInfo).toString();
                entry.icon = applicationInfo.loadIcon(packageManager);
                entry.isSystem = (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                String suffix = targets.get(applicationInfo.packageName);
                entry.inTarget = suffix != null && !"-".equals(suffix);
                entry.mode = modeFromSuffix(suffix);
                entries.add(entry);
            }

            // Keep packages from the saved list that are not installed, so leaving this
            // screen cannot drop them, and so the user can remove them.
            Drawable fallbackIcon = packageManager.getDefaultActivityIcon();
            for (Map.Entry<String, String> extra : targets.entrySet()) {
                if (installedPackages.contains(extra.getKey()) || "-".equals(extra.getValue())) {
                    continue;
                }
                AppEntry entry = new AppEntry();
                entry.packageName = extra.getKey();
                entry.label = extra.getKey();
                entry.icon = fallbackIcon;
                entry.inTarget = true;
                entry.mode = modeFromSuffix(extra.getValue());
                entries.add(entry);
            }

            if (getActivity() == null) {
                return;
            }
            getActivity().runOnUiThread(() -> {
                if (!isAdded()) {
                    return;
                }
                mTargets.clear();
                mTargets.putAll(targets);
                mAllApps.clear();
                mAllApps.addAll(entries);
                sortApps();
                mLoaded = true;
                filterApps();
            });
        }).start();
    }

    private void sortApps() {
        mAllApps.sort((a, b) -> {
            if (a.inTarget != b.inTarget) {
                return a.inTarget ? -1 : 1;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
    }

    private void filterApps() {
        if (!isAdded() || getContext() == null) {
            return;
        }

        List<AppEntry> filtered = new ArrayList<>();
        String query = mSearchQuery.toLowerCase();
        for (AppEntry entry : mAllApps) {
            if (!mShowSystem && entry.isSystem && !entry.inTarget) {
                continue;
            }
            if (!query.isEmpty()) {
                String label = entry.label.toLowerCase();
                String pkg = entry.packageName.toLowerCase();
                if (!label.contains(query) && !pkg.contains(query)) {
                    continue;
                }
            }
            filtered.add(entry);
        }
        mFilteredApps.clear();
        mFilteredApps.addAll(filtered);
        if (mSelectedCount != null) {
            int selectedCount = 0;
            for (AppEntry entry : mAllApps) {
                if (entry.inTarget) {
                    selectedCount++;
                }
            }
            mSelectedCount.setText(getString(
                    R.string.trickystore_target_selected_count, selectedCount));
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void onTargetChanged(@NonNull AppEntry entry, boolean selected) {
        entry.inTarget = selected;
        if (!selected) {
            entry.mode = TargetMode.AUTO;
            mTargets.remove(entry.packageName);
        } else {
            mTargets.put(entry.packageName, entry.mode.symbol);
        }
        sortApps();
        filterApps();
        if (persistSelection()) {
            mNeedsRestart = true;
        }
    }

    private void onModeChanged(@NonNull AppEntry entry, @NonNull TargetMode mode) {
        entry.mode = mode;
        if (entry.inTarget) {
            mTargets.put(entry.packageName, mode.symbol);
            if (persistSelection()) {
                mNeedsRestart = true;
            }
        }
    }

    /**
     * Writes the current selection. Returns true only when the stored value changed.
     * Matching the framework default clears the setting. Selecting nothing writes
     * {@link #NONE_SELECTED} so an empty value is not filled back in with the defaults.
     */
    private boolean persistSelection() {
        if (!mLoaded) {
            return false;
        }
        Context context = getContext();
        if (context == null) {
            return false;
        }

        String serialized = serialize(mTargets);
        String defaults = serialize(parseTargets(TrickyStoreService.DEFAULT_TARGET_LIST));
        final String toStore;
        if (serialized.equals(defaults)) {
            toStore = null;
        } else if (serialized.isEmpty()) {
            toStore = NONE_SELECTED;
        } else {
            toStore = serialized;
        }

        String current = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET);
        if (effectiveTargets(current).equals(effectiveTargets(toStore))) {
            return false;
        }

        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.SPOOF_TRICKYSTORE_TARGET, toStore);
        return true;
    }

    private void forceStopClients() {
        TrickyStoreClients.forceStop(getContext());
    }

    private Set<String> effectiveTargets(@Nullable String stored) {
        if (stored == null || stored.isEmpty()) {
            return targetSet(parseTargets(TrickyStoreService.DEFAULT_TARGET_LIST));
        }
        return targetSet(parseTargets(stored));
    }

    private static Set<String> targetSet(Map<String, String> targets) {
        Set<String> set = new HashSet<>();
        for (Map.Entry<String, String> entry : targets.entrySet()) {
            set.add(entry.getKey() + entry.getValue());
        }
        return set;
    }

    private static String serialize(Map<String, String> targets) {
        List<String> packages = new ArrayList<>();
        for (Map.Entry<String, String> entry : targets.entrySet()) {
            packages.add(entry.getKey() + entry.getValue());
        }
        Collections.sort(packages);
        return String.join("\n", packages);
    }

    private static Map<String, String> parseTargets(@Nullable String content) {
        Map<String, String> targets = new LinkedHashMap<>();
        if (content == null) {
            return targets;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("[")) {
            parseJsonTargets(trimmed, targets);
            return targets;
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String suffix = "";
            String pkg = line;
            char last = line.charAt(line.length() - 1);
            if (last == '?' || last == '!' || last == '-') {
                suffix = line.substring(line.length() - 1);
                pkg = line.substring(0, line.length() - 1).trim();
            }
            if (!pkg.isEmpty()) {
                targets.put(pkg, suffix);
            }
        }
        return targets;
    }

    private static void parseJsonTargets(String content, Map<String, String> targets) {
        try {
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String pkg = obj.optString("package", "").trim();
                if (pkg.isEmpty()) {
                    continue;
                }
                targets.put(pkg, suffixForMode(obj.optString("mode", "AUTO")));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring unreadable target list", e);
        }
    }

    private static String suffixForMode(String mode) {
        if ("LEAF_HACK".equalsIgnoreCase(mode)) {
            return "?";
        }
        if ("GENERATE".equalsIgnoreCase(mode) || "CERT_GEN".equalsIgnoreCase(mode)) {
            return "!";
        }
        if ("SKIP".equalsIgnoreCase(mode)) {
            return "-";
        }
        return "";
    }

    private static TargetMode modeFromSuffix(@Nullable String suffix) {
        if ("?".equals(suffix)) {
            return TargetMode.LEAF_HACK;
        }
        if ("!".equals(suffix)) {
            return TargetMode.CERT_GEN;
        }
        return TargetMode.AUTO;
    }

    private static final class AppEntry {
        String packageName;
        String label;
        Drawable icon;
        boolean isSystem;
        boolean inTarget;
        TargetMode mode = TargetMode.AUTO;
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.trickystore_target_app_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppEntry entry = mFilteredApps.get(position);

            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.packageName.setText(entry.packageName);

            holder.enabled.setOnCheckedChangeListener(null);
            holder.modeGroup.setOnCheckedChangeListener(null);

            holder.enabled.setChecked(entry.inTarget);
            holder.modeGroup.setVisibility(entry.inTarget ? View.VISIBLE : View.GONE);
            if (entry.mode == TargetMode.LEAF_HACK) {
                holder.modeGroup.check(R.id.trickystore_target_mode_leaf);
            } else if (entry.mode == TargetMode.CERT_GEN) {
                holder.modeGroup.check(R.id.trickystore_target_mode_generate);
            } else {
                holder.modeGroup.check(R.id.trickystore_target_mode_auto);
            }

            holder.enabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                    onTargetChanged(entry, isChecked));
            holder.itemView.setOnClickListener(v -> holder.enabled.toggle());
            holder.modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                if (checkedId == R.id.trickystore_target_mode_leaf) {
                    onModeChanged(entry, TargetMode.LEAF_HACK);
                } else if (checkedId == R.id.trickystore_target_mode_generate) {
                    onModeChanged(entry, TargetMode.CERT_GEN);
                } else if (checkedId == R.id.trickystore_target_mode_auto) {
                    onModeChanged(entry, TargetMode.AUTO);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mFilteredApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView packageName;
            final CheckBox enabled;
            final RadioGroup modeGroup;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.trickystore_target_icon);
                label = itemView.findViewById(R.id.trickystore_target_label);
                packageName = itemView.findViewById(R.id.trickystore_target_package);
                enabled = itemView.findViewById(R.id.trickystore_target_enabled);
                modeGroup = itemView.findViewById(R.id.trickystore_target_mode);
            }
        }
    }
}
