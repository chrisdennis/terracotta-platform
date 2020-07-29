/*
 * Copyright Terracotta, Inc.
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
package org.terracotta.dynamic_config.api.model;

import java.util.Properties;
import java.util.stream.Stream;

/**
 * @author Mathieu Carbou
 */
public interface PropertyHolder {
  Scope getScope();

  default Properties toProperties(boolean expanded, boolean includeDefaultValues) {
    return toProperties(expanded, includeDefaultValues, false);
  }

  Properties toProperties(boolean expanded, boolean includeDefaultValues, boolean includeHiddenSettings);

  default Stream<? extends PropertyHolder> descendants() {
    return Stream.empty();
  }
}
