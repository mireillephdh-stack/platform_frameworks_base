/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.desktopmode.education.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import com.android.framework.protobuf.InvalidProtocolBufferException
import com.android.internal.annotations.VisibleForTesting
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.first

/**
 * Manages interactions with the App Handle education datastore.
 *
 * This class provides a layer of abstraction between the UI/business logic and the underlying
 * DataStore.
 */
class AppHandleEducationDatastoreRepository
@VisibleForTesting
constructor(private val dataStore: DataStore<WindowingEducationProto>) {
  constructor(
      context: Context
  ) : this(
      DataStoreFactory.create(
          serializer = WindowingEducationProtoSerializer,
          produceFile = { context.dataStoreFile(APP_HANDLE_EDUCATION_DATASTORE_FILEPATH) }))

  /**
   * Reads and returns the [WindowingEducationProto] Proto object from the DataStore. If the
   * DataStore is empty or there's an error reading, it returns the default value of Proto.
   */
  suspend fun windowingEducationProto(): WindowingEducationProto =
      try {
        dataStore.data.first()
      } catch (e: Exception) {
        Log.e(TAG, "Unable to read from datastore")
        WindowingEducationProto.getDefaultInstance()
      }

  companion object {
    private const val TAG = "AppHandleEducationDatastoreRepository"
    private const val APP_HANDLE_EDUCATION_DATASTORE_FILEPATH = "app_handle_education.pb"

    object WindowingEducationProtoSerializer : Serializer<WindowingEducationProto> {

      override val defaultValue: WindowingEducationProto =
          WindowingEducationProto.getDefaultInstance()

      override suspend fun readFrom(input: InputStream): WindowingEducationProto =
          try {
            WindowingEducationProto.parseFrom(input)
          } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
          }

      override suspend fun writeTo(windowingProto: WindowingEducationProto, output: OutputStream) =
          windowingProto.writeTo(output)
    }
  }
}
