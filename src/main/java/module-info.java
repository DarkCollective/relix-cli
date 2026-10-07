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
/**
 * The {@code relix} command.
 *
 * <p>Everything here is the command's implementation, not an API: the module exports
 * nothing. It reaches the engine only through the packages the engine's own module
 * descriptors export, which the compiler enforces here. The commands are opened to
 * picocli alone, which reads their options by reflection.
 */
module com.darkcollective.relix.cli {
    requires com.darkcollective.relix;
    requires com.darkcollective.relix.docs;
    requires tools.jackson.core;
    requires info.picocli;
    requires java.sql;

    opens com.darkcollective.relix.cli.command to info.picocli;
}
