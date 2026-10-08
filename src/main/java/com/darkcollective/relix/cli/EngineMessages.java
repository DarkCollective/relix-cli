/*
 * Copyright 2026 Darkcollective, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.darkcollective.relix.cli;

import com.darkcollective.relix.embed.RelixException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The message of a failure the engine raised, as the command prints it.
 *
 * <p>The engine writes for a program that embeds it, so the remedy it names may be a
 * Java call. Where the command has its own way to give what is missing, the message names
 * that instead; every other message is the engine's own.
 */
public final class EngineMessages {

    /** The engine's unbound-parameter message, whose remedy is {@code Relix.Builder.parameters}. */
    private static final Pattern UNBOUND =
            Pattern.compile("parameter \\$(\\S+) is not bound; give a value with .*");

    private EngineMessages() {
    }

    /**
     * The message to print for a failure the engine raised.
     *
     * @param failure what the engine threw
     * @return its message, its remedy put in the command's terms
     */
    public static String of(RelixException failure) {
        String message = failure.getMessage();
        Matcher unbound = UNBOUND.matcher(message);
        if (unbound.matches()) {
            String name = unbound.group(1);
            return "parameter $" + name + " is not bound; give it a value with -a " + name + "=VALUE";
        }
        return message;
    }
}
