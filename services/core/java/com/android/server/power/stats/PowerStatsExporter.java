/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import android.os.AggregateBatteryConsumer;
import android.os.BatteryConsumer;
import android.os.BatteryUsageStats;
import android.os.UidBatteryConsumer;
import android.util.Slog;

import com.android.internal.os.PowerStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Given a time range, converts accumulated PowerStats to BatteryUsageStats.  Combines
 * stores spans of PowerStats with the yet-unprocessed tail of battery history.
 */
public class PowerStatsExporter {
    private static final String TAG = "PowerStatsExporter";
    private final PowerStatsStore mPowerStatsStore;
    private final PowerStatsAggregator mPowerStatsAggregator;
    private final long mBatterySessionTimeSpanSlackMillis;
    private static final long BATTERY_SESSION_TIME_SPAN_SLACK_MILLIS = TimeUnit.MINUTES.toMillis(2);

    public PowerStatsExporter(PowerStatsStore powerStatsStore,
            PowerStatsAggregator powerStatsAggregator) {
        this(powerStatsStore, powerStatsAggregator, BATTERY_SESSION_TIME_SPAN_SLACK_MILLIS);
    }

    public PowerStatsExporter(PowerStatsStore powerStatsStore,
            PowerStatsAggregator powerStatsAggregator,
            long batterySessionTimeSpanSlackMillis) {
        mPowerStatsStore = powerStatsStore;
        mPowerStatsAggregator = powerStatsAggregator;
        mBatterySessionTimeSpanSlackMillis = batterySessionTimeSpanSlackMillis;
    }

    /**
     * Populates the provided BatteryUsageStats.Builder with power estimates from the accumulated
     * PowerStats, both stored in PowerStatsStore and not-yet processed.
     */
    public void exportAggregatedPowerStats(BatteryUsageStats.Builder batteryUsageStatsBuilder,
            long monotonicStartTime, long monotonicEndTime) {
        boolean hasStoredSpans = false;
        long maxEndTime = monotonicStartTime;
        List<PowerStatsSpan.Metadata> spans = mPowerStatsStore.getTableOfContents();
        for (int i = spans.size() - 1; i >= 0; i--) {
            PowerStatsSpan.Metadata metadata = spans.get(i);
            if (!metadata.getSections().contains(AggregatedPowerStatsSection.TYPE)) {
                continue;
            }

            List<PowerStatsSpan.TimeFrame> timeFrames = metadata.getTimeFrames();
            long spanMinTime = Long.MAX_VALUE;
            long spanMaxTime = Long.MIN_VALUE;
            for (int j = 0; j < timeFrames.size(); j++) {
                PowerStatsSpan.TimeFrame timeFrame = timeFrames.get(j);
                long startMonotonicTime = timeFrame.startMonotonicTime;
                long endMonotonicTime = startMonotonicTime + timeFrame.duration;
                if (startMonotonicTime < spanMinTime) {
                    spanMinTime = startMonotonicTime;
                }
                if (endMonotonicTime > spanMaxTime) {
                    spanMaxTime = endMonotonicTime;
                }
            }

            if (!(spanMinTime >= monotonicStartTime && spanMaxTime < monotonicEndTime)) {
                continue;
            }

            if (spanMaxTime > maxEndTime) {
                maxEndTime = spanMaxTime;
            }

            PowerStatsSpan span = mPowerStatsStore.loadPowerStatsSpan(metadata.getId(),
                    AggregatedPowerStatsSection.TYPE);
            if (span == null) {
                Slog.e(TAG, "Could not read PowerStatsStore section " + metadata);
                continue;
            }
            List<PowerStatsSpan.Section> sections = span.getSections();
            for (int k = 0; k < sections.size(); k++) {
                hasStoredSpans = true;
                PowerStatsSpan.Section section = sections.get(k);
                populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder,
                        ((AggregatedPowerStatsSection) section).getAggregatedPowerStats());
            }
        }

        if (!hasStoredSpans || maxEndTime < monotonicEndTime - mBatterySessionTimeSpanSlackMillis) {
            mPowerStatsAggregator.aggregatePowerStats(maxEndTime, monotonicEndTime,
                    stats -> populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder, stats));
        }
        mPowerStatsAggregator.reset();
    }

    private void populateBatteryUsageStatsBuilder(
            BatteryUsageStats.Builder batteryUsageStatsBuilder, AggregatedPowerStats stats) {
        List<PowerComponentAggregatedPowerStats> powerComponentStats =
                stats.getPowerComponentStats();
        for (int i = powerComponentStats.size() - 1; i >= 0; i--) {
            populateBatteryUsageStatsBuilder(batteryUsageStatsBuilder, powerComponentStats.get(i));
        }
    }

    private static void populateBatteryUsageStatsBuilder(
            BatteryUsageStats.Builder batteryUsageStatsBuilder,
            PowerComponentAggregatedPowerStats powerComponentStats) {
        PowerStats.Descriptor descriptor = powerComponentStats.getPowerStatsDescriptor();
        if (descriptor == null) {
            return;
        }
        boolean isCustomComponent =
                descriptor.powerComponentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;

        PowerStatsLayout layout = new PowerStatsLayout();
        layout.fromExtras(descriptor.extras);

        long[] deviceStats = new long[descriptor.statsArrayLength];
        for (int screenState = 0; screenState < BatteryConsumer.SCREEN_STATE_COUNT; screenState++) {
            if (batteryUsageStatsBuilder.isScreenStateDataNeeded()) {
                if (screenState == BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                    continue;
                }
            } else if (screenState != BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                continue;
            }

            for (int powerState = 0; powerState < BatteryConsumer.POWER_STATE_COUNT; powerState++) {
                if (batteryUsageStatsBuilder.isPowerStateDataNeeded() && !isCustomComponent) {
                    if (powerState == BatteryConsumer.POWER_STATE_UNSPECIFIED) {
                        continue;
                    }
                } else if (powerState != BatteryConsumer.POWER_STATE_BATTERY) {
                    continue;
                }

                populateAggregatedBatteryConsumer(batteryUsageStatsBuilder, powerComponentStats,
                        layout, deviceStats, screenState, powerState);
            }
        }
        if (layout.isUidPowerAttributionSupported()) {
            populateBatteryConsumers(batteryUsageStatsBuilder,
                    powerComponentStats, layout);
        }
    }

    private static void populateAggregatedBatteryConsumer(
            BatteryUsageStats.Builder batteryUsageStatsBuilder,
            PowerComponentAggregatedPowerStats powerComponentStats, PowerStatsLayout layout,
            long[] deviceStats, @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState) {
        int powerComponentId = powerComponentStats.powerComponentId;
        boolean isCustomComponent =
                powerComponentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;

        double[] totalPower = new double[1];
        MultiStateStats.States.forEachTrackedStateCombination(
                powerComponentStats.getConfig().getDeviceStateConfig(),
                states -> {
                    if (!areMatchingStates(states, screenState, powerState)) {
                        return;
                    }

                    if (!powerComponentStats.getDeviceStats(deviceStats, states)) {
                        return;
                    }

                    totalPower[0] += layout.getDevicePowerEstimate(deviceStats);
                });

        AggregateBatteryConsumer.Builder deviceScope =
                batteryUsageStatsBuilder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_DEVICE);
        if (isCustomComponent) {
            if (batteryUsageStatsBuilder.isSupportedCustomPowerComponent(powerComponentId)) {
                deviceScope.addConsumedPowerForCustomComponent(powerComponentId, totalPower[0]);
            }
        } else {
            BatteryConsumer.Key key = deviceScope.getKey(powerComponentId,
                    BatteryConsumer.PROCESS_STATE_ANY, screenState, powerState);
            if (key != null) {
                deviceScope.addConsumedPower(key, totalPower[0],
                        BatteryConsumer.POWER_MODEL_UNDEFINED);
            }
            deviceScope.addConsumedPower(powerComponentId, totalPower[0],
                    BatteryConsumer.POWER_MODEL_UNDEFINED);
        }
    }

    private static void populateBatteryConsumers(
            BatteryUsageStats.Builder batteryUsageStatsBuilder,
            PowerComponentAggregatedPowerStats powerComponentStats,
            PowerStatsLayout layout) {
        AggregatedPowerStatsConfig.PowerComponent powerComponent = powerComponentStats.getConfig();
        int powerComponentId = powerComponent.getPowerComponentId();
        PowerStats.Descriptor descriptor = powerComponentStats.getPowerStatsDescriptor();
        long[] uidStats = new long[descriptor.uidStatsArrayLength];

        // TODO(b/347101393): add support for per-procstate breakdown for custom energy consumers
        boolean breakDownByProcState = batteryUsageStatsBuilder.isProcessStateDataNeeded()
                && powerComponent
                .getUidStateConfig()[AggregatedPowerStatsConfig.STATE_PROCESS_STATE].isTracked()
                && powerComponentId < BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID;

        ArrayList<Integer> uids = new ArrayList<>();
        powerComponentStats.collectUids(uids);
        for (int screenState = 0; screenState < BatteryConsumer.SCREEN_STATE_COUNT; screenState++) {
            if (batteryUsageStatsBuilder.isScreenStateDataNeeded()) {
                if (screenState == BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                    continue;
                }
            } else if (screenState != BatteryConsumer.SCREEN_STATE_UNSPECIFIED) {
                continue;
            }

            for (int powerState = 0; powerState < BatteryConsumer.POWER_STATE_COUNT; powerState++) {
                if (batteryUsageStatsBuilder.isPowerStateDataNeeded()) {
                    if (powerState == BatteryConsumer.POWER_STATE_UNSPECIFIED) {
                        continue;
                    }
                } else if (powerState != BatteryConsumer.POWER_STATE_BATTERY) {
                    continue;
                }

                populateUidBatteryConsumers(batteryUsageStatsBuilder, powerComponentStats, layout,
                        uids, powerComponent, uidStats, breakDownByProcState, screenState,
                        powerState);
            }
        }
    }

    private static void populateUidBatteryConsumers(
            BatteryUsageStats.Builder batteryUsageStatsBuilder,
            PowerComponentAggregatedPowerStats powerComponentStats, PowerStatsLayout layout,
            List<Integer> uids, AggregatedPowerStatsConfig.PowerComponent powerComponent,
            long[] uidStats, boolean breakDownByProcState,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState) {
        int powerComponentId = powerComponentStats.powerComponentId;
        double[] powerByProcState =
                new double[breakDownByProcState ? BatteryConsumer.PROCESS_STATE_COUNT : 1];
        double powerAllApps = 0;
        for (int uid : uids) {
            UidBatteryConsumer.Builder builder =
                    batteryUsageStatsBuilder.getOrCreateUidBatteryConsumerBuilder(uid);

            Arrays.fill(powerByProcState, 0);

            MultiStateStats.States.forEachTrackedStateCombination(
                    powerComponent.getUidStateConfig(),
                    states -> {
                        if (!areMatchingStates(states, screenState, powerState)) {
                            return;
                        }

                        if (!powerComponentStats.getUidStats(uidStats, uid, states)) {
                            return;
                        }

                        double power = layout.getUidPowerEstimate(uidStats);
                        int procState = breakDownByProcState
                                ? states[AggregatedPowerStatsConfig.STATE_PROCESS_STATE]
                                : BatteryConsumer.PROCESS_STATE_UNSPECIFIED;
                        powerByProcState[procState] += power;
                    });

            double powerAllProcStates = 0;
            for (int procState = 0; procState < powerByProcState.length; procState++) {
                double power = powerByProcState[procState];
                if (power == 0) {
                    continue;
                }
                powerAllProcStates += power;
                if (breakDownByProcState
                        && procState != BatteryConsumer.PROCESS_STATE_UNSPECIFIED) {
                    if (batteryUsageStatsBuilder.isPowerStateDataNeeded()) {
                        builder.addConsumedPower(
                                builder.getKey(powerComponentId, procState, screenState,
                                        powerState),
                                power, BatteryConsumer.POWER_MODEL_UNDEFINED);
                    } else {
                        builder.addConsumedPower(
                                builder.getKey(powerComponentId, procState, screenState,
                                        BatteryConsumer.POWER_STATE_UNSPECIFIED),
                                power, BatteryConsumer.POWER_MODEL_UNDEFINED);
                    }
                }
            }
            if (powerComponentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
                if (batteryUsageStatsBuilder.isSupportedCustomPowerComponent(powerComponentId)) {
                    builder.addConsumedPowerForCustomComponent(powerComponentId,
                            powerAllProcStates);
                }
            } else {
                builder.addConsumedPower(powerComponentId, powerAllProcStates,
                        BatteryConsumer.POWER_MODEL_UNDEFINED);
            }
            powerAllApps += powerAllProcStates;
        }

        AggregateBatteryConsumer.Builder allAppsScope =
                batteryUsageStatsBuilder.getAggregateBatteryConsumerBuilder(
                        BatteryUsageStats.AGGREGATE_BATTERY_CONSUMER_SCOPE_ALL_APPS);
        if (powerComponentId >= BatteryConsumer.FIRST_CUSTOM_POWER_COMPONENT_ID) {
            if (batteryUsageStatsBuilder.isSupportedCustomPowerComponent(powerComponentId)) {
                allAppsScope.addConsumedPowerForCustomComponent(powerComponentId, powerAllApps);
            }
        } else {
            BatteryConsumer.Key key = allAppsScope.getKey(powerComponentId,
                    BatteryConsumer.PROCESS_STATE_ANY, screenState, powerState);
            if (key != null) {
                allAppsScope.addConsumedPower(key, powerAllApps,
                        BatteryConsumer.POWER_MODEL_UNDEFINED);
            }
            allAppsScope.addConsumedPower(powerComponentId, powerAllApps,
                    BatteryConsumer.POWER_MODEL_UNDEFINED);
        }
    }

    private static boolean areMatchingStates(int[] states,
            @BatteryConsumer.ScreenState int screenState,
            @BatteryConsumer.PowerState int powerState) {
        switch (screenState) {
            case BatteryConsumer.SCREEN_STATE_ON:
                if (states[AggregatedPowerStatsConfig.STATE_SCREEN]
                        != AggregatedPowerStatsConfig.SCREEN_STATE_ON) {
                    return false;
                }
                break;
            case BatteryConsumer.SCREEN_STATE_OTHER:
                if (states[AggregatedPowerStatsConfig.STATE_SCREEN]
                        != AggregatedPowerStatsConfig.SCREEN_STATE_OTHER) {
                    return false;
                }
                break;
        }

        switch (powerState) {
            case BatteryConsumer.POWER_STATE_BATTERY:
                if (states[AggregatedPowerStatsConfig.STATE_POWER]
                        != AggregatedPowerStatsConfig.POWER_STATE_BATTERY) {
                    return false;
                }
                break;
            case BatteryConsumer.POWER_STATE_OTHER:
                if (states[AggregatedPowerStatsConfig.STATE_POWER]
                        != AggregatedPowerStatsConfig.POWER_STATE_OTHER) {
                    return false;
                }
                break;
        }
        return true;
    }
}
