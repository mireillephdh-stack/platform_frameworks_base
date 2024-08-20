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

package android.app.appfunctions;

import android.app.appfunctions.ExecuteAppFunctionAidlRequest;
import android.app.appfunctions.IExecuteAppFunctionCallback;

/**
* Interface between an app and the server implementation service (AppFunctionManagerService).
* @hide
*/
oneway interface IAppFunctionManager {
    /**
    * Executes an app function provided by {@link AppFunctionService} through the system.
    *
    * @param request the request to execute an app function.
    * @param callback the callback to report the result.
    */
    void executeAppFunction(
        in ExecuteAppFunctionAidlRequest request,
        in IExecuteAppFunctionCallback callback
    );
}