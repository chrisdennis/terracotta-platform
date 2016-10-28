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
package org.terracotta.management.service.monitoring;

import org.terracotta.entity.ClientCommunicator;
import org.terracotta.entity.ClientDescriptor;
import org.terracotta.entity.MessageCodecException;
import org.terracotta.management.model.message.ManagementCallMessage;
import org.terracotta.voltron.proxy.ProxyEntityResponse;

/**
 * @author Mathieu Carbou
 */
class ManagementCommunicator {

  private final ClientCommunicator clientCommunicator;

  ManagementCommunicator(ClientCommunicator clientCommunicator) {
    this.clientCommunicator = clientCommunicator;
  }

  void send(ClientDescriptor target, ManagementCallMessage message) {
    try {
      clientCommunicator.sendNoResponse(target, ProxyEntityResponse.response(ManagementCallMessage.class, message));
    } catch (MessageCodecException e) {
      throw new RuntimeException(e);
    }
  }

}
