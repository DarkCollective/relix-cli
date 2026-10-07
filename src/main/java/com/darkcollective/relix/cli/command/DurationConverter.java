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
package com.darkcollective.relix.cli.command;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A duration as a person types one: a number and a unit ({@code 500ms}, {@code 30s},
 * {@code 5m}, {@code 1h}), or ISO-8601 ({@code PT30S}).
 */
final class DurationConverter implements ITypeConverter<Duration> {

    private static final Pattern SHORT = Pattern.compile("(\\d+)(ms|s|m|h)");

    @Override
    public Duration convert(String value) {
        String text = value.strip().toLowerCase(Locale.ROOT);
        Matcher m = SHORT.matcher(text);
        Duration duration;
        if (m.matches()) {
            long amount = Long.parseLong(m.group(1));
            duration = switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofHours(amount);
            };
        } else {
            try {
                duration = Duration.parse(value.strip());
            } catch (DateTimeParseException e) {
                throw new TypeConversionException(
                        "'" + value + "' is not a duration (such as 500ms, 30s, 5m, 1h or PT30S)");
            }
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new TypeConversionException("'" + value + "' is not a positive duration");
        }
        return duration;
    }
}
