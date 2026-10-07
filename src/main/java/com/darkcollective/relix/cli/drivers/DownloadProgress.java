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
package com.darkcollective.relix.cli.drivers;

import com.darkcollective.relix.connectors.std.HttpFetcher;
import com.darkcollective.relix.processor.connector.Fetcher;

import java.io.PrintWriter;

/**
 * Builds a {@link Fetcher} that announces each download to the terminal before it
 * starts, so a user running {@code relix} interactively understands what a pause is
 * (e.g. fetching a JDBC driver or a connector plugin from the network).
 *
 * <p>The message is written to {@code stderr} (leaving {@code stdout} clean for
 * query results / machine-readable output) and flushed immediately so it appears
 * before the blocking transfer rather than after it.
 */
public final class DownloadProgress {

    private DownloadProgress() {
    }

    /**
     * Wraps the production HTTPS {@link Fetcher} with a one-line progress notice.
     *
     * @param err the stream to announce downloads on
     * @return a progress-reporting fetcher
     */
    public static Fetcher reporting(PrintWriter err) {
        Fetcher delegate = HttpFetcher.https();
        return uri -> {
            err.println("relix: downloading " + uri + " …");
            err.flush();
            return delegate.fetch(uri);
        };
    }
}
