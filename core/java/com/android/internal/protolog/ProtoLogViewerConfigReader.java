package com.android.internal.protolog;

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.GROUPS;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.Group.NAME;

import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MESSAGES;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.MESSAGE_ID;
import static android.internal.perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.GROUP_ID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.proto.ProtoInputStream;

import com.android.internal.protolog.common.ILogger;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public class ProtoLogViewerConfigReader {
    @NonNull
    private final ViewerConfigInputStreamProvider mViewerConfigInputStreamProvider;
    private final Map<String, Set<Long>> mGroupHashes = new TreeMap<>();
    private final LongSparseArray<String> mLogMessageMap = new LongSparseArray<>();

    public ProtoLogViewerConfigReader(
            @NonNull ViewerConfigInputStreamProvider viewerConfigInputStreamProvider) {
        this.mViewerConfigInputStreamProvider = viewerConfigInputStreamProvider;
    }

    /**
     * Returns message format string for its hash or null if unavailable
     * or the viewer config is not loaded into memory.
     */
    public synchronized String getViewerString(long messageHash) {
        return mLogMessageMap.get(messageHash);
    }

    public synchronized void loadViewerConfig(String[] groups) {
        loadViewerConfig(groups, (message) -> {});
    }

    /**
     * Loads the viewer config into memory. No-op if already loaded in memory.
     */
    public synchronized void loadViewerConfig(String[] groups, @NonNull ILogger logger) {
        for (String group : groups) {
            if (mGroupHashes.containsKey(group)) {
                continue;
            }

            try {
                Map<Long, String> mappings = loadViewerConfigMappingForGroup(group);
                mGroupHashes.put(group, mappings.keySet());
                for (Long key : mappings.keySet()) {
                    mLogMessageMap.put(key, mappings.get(key));
                }

                logger.log("Loaded " + mLogMessageMap.size() + " log definitions");
            } catch (IOException e) {
                logger.log("Unable to load log definitions: "
                        + "IOException while processing viewer config" + e);
            }
        }
    }

    public synchronized void unloadViewerConfig(String[] groups) {
        unloadViewerConfig(groups, (message) -> {});
    }

    /**
     * Unload the viewer config from memory.
     */
    public synchronized void unloadViewerConfig(String[] groups, @NonNull ILogger logger) {
        for (String group : groups) {
            if (!mGroupHashes.containsKey(group)) {
                continue;
            }

            final Set<Long> hashes = mGroupHashes.get(group);
            for (Long hash : hashes) {
                logger.log("Unloading viewer config hash " + hash);
                mLogMessageMap.remove(hash);
            }
        }
    }

    private Map<Long, String> loadViewerConfigMappingForGroup(String group) throws IOException {
        Long targetGroupId = loadGroupId(group);

        final Map<Long, String> hashesForGroup = new TreeMap<>();
        final ProtoInputStream pis = mViewerConfigInputStreamProvider.getInputStream();

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (pis.getFieldNumber() == (int) MESSAGES) {
                final long inMessageToken = pis.start(MESSAGES);

                long messageId = 0;
                String message = null;
                int groupId = 0;
                while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (pis.getFieldNumber()) {
                        case (int) MESSAGE_ID:
                            messageId = pis.readLong(MESSAGE_ID);
                            break;
                        case (int) MESSAGE:
                            message = pis.readString(MESSAGE);
                            break;
                        case (int) GROUP_ID:
                            groupId = pis.readInt(GROUP_ID);
                            break;
                    }
                }

                if (groupId == 0) {
                    throw new IOException("Failed to get group id");
                }

                if (messageId == 0) {
                    throw new IOException("Failed to get message id");
                }

                if (message == null) {
                    throw new IOException("Failed to get message string");
                }

                if (groupId == targetGroupId) {
                    hashesForGroup.put(messageId, message);
                }

                pis.end(inMessageToken);
            }
        }

        return hashesForGroup;
    }

    private Long loadGroupId(String group) throws IOException {
        final ProtoInputStream pis = mViewerConfigInputStreamProvider.getInputStream();

        while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
            if (pis.getFieldNumber() == (int) GROUPS) {
                final long inMessageToken = pis.start(GROUPS);

                long groupId = 0;
                String groupName = null;
                while (pis.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                    switch (pis.getFieldNumber()) {
                        case (int) ID:
                            groupId = pis.readInt(ID);
                            break;
                        case (int) NAME:
                            groupName = pis.readString(NAME);
                            break;
                    }
                }

                if (Objects.equals(groupName, group)) {
                    return groupId;
                }

                pis.end(inMessageToken);
            }
        }

        throw new RuntimeException("Group " + group + "not found in viewer config");
    }
}
