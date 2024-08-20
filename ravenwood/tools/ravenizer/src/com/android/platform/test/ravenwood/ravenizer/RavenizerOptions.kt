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
package com.android.platform.test.ravenwood.ravenizer

import com.android.hoststubgen.ArgIterator
import com.android.hoststubgen.ArgumentsException
import com.android.hoststubgen.SetOnce
import com.android.hoststubgen.ensureFileExists
import com.android.hoststubgen.log

class RavenizerOptions(
    /** Input jar file*/
    var inJar: SetOnce<String> = SetOnce(""),

    /** Output jar file */
    var outJar: SetOnce<String> = SetOnce(""),
) {
    companion object {
        fun parseArgs(args: Array<String>): RavenizerOptions {
            val ret = RavenizerOptions()
            val ai = ArgIterator.withAtFiles(args)

            while (true) {
                val arg = ai.nextArgOptional()
                if (arg == null) {
                    break
                }

                fun nextArg(): String = ai.nextArgRequired(arg)

                if (log.maybeHandleCommandLineArg(arg) { nextArg() }) {
                    continue
                }
                try {
                    when (arg) {
                        // TODO: Write help
                        "-h", "--help" -> TODO("Help is not implemented yet")

                        "--in-jar" -> ret.inJar.set(nextArg()).ensureFileExists()
                        "--out-jar" -> ret.outJar.set(nextArg())

                        else -> throw ArgumentsException("Unknown option: $arg")
                    }
                } catch (e: SetOnce.SetMoreThanOnceException) {
                    throw ArgumentsException("Duplicate or conflicting argument found: $arg")
                }
            }

            if (!ret.inJar.isSet) {
                throw ArgumentsException("Required option missing: --in-jar")
            }
            if (!ret.outJar.isSet) {
                throw ArgumentsException("Required option missing: --out-jar")
            }
           return ret
        }
    }

    override fun toString(): String {
        return """
            RavenizerOptions{
              inJar=$inJar,
              outJar=$outJar,
            }
            """.trimIndent()
    }
}
