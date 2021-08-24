/*
 * Copyright (c) 2020-2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository at
 * https://github.com/hyperledger-labs/business-partner-agent
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
package org.hyperledger.bpa.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.hyperledger.aries.api.connection.ReceiveInvitationRequest;
import org.hyperledger.aries.api.out_of_band.InvitationMessage;
import org.hyperledger.bpa.api.exception.InvitationException;
import org.hyperledger.bpa.controller.api.invitation.CheckInvitationResponse;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@NoArgsConstructor
@Singleton
public class InvitationParser {

    OkHttpClient httpClient = new OkHttpClient.Builder().followRedirects(false).build();

    @Inject
    ObjectMapper mapper;

    @Data
    public static final class Invitation {
        private boolean oob;
        private boolean parsed;
        private ReceiveInvitationRequest invitationRequest;
        private InvitationMessage invitationMessage;
        private String error;
        private String invitationBlock;
        private Map<String, Object> invitation;
    }

    // take a url, determine if it is an invitation, and if so, what type and can it
    // be handled?
    public CheckInvitationResponse checkInvitation(@NonNull String invitationUrl) {
        HttpUrl url = HttpUrl.parse(URLDecoder.decode(invitationUrl, StandardCharsets.UTF_8));
        String invitationBlock = null;
        if (url != null) {
            invitationBlock = parseInvitationBlock(url);
            if (StringUtils.isEmpty(invitationBlock)) {
                invitationBlock = parseInvitationBlockFromRedirect(url);
            }

            Invitation invite = parseInvitation(invitationBlock);

            if (StringUtils.isNotEmpty(invite.getError())) {
                throw new InvitationException(invite.getError());
            } else {
                // right now there is only one option for a success
                if (invite.isParsed() && invite.getInvitationRequest() != null) {
                    ReceiveInvitationRequest r = invite.getInvitationRequest();
                    return CheckInvitationResponse.builder()
                            .label(r.getLabel())
                            .invitation(invite.getInvitation())
                            .invitationBlock(invite.getInvitationBlock())
                            .build();
                }
            }
        } else {
            throw new InvitationException(String.format(
                    "Invitation Url could not be decoded. Cannot determine invitation details.", invitationUrl));
        }
        return null;
    }

    public Invitation parseInvitation(@NonNull String invitationBlock) {
        Invitation invitation = new Invitation();
        if (StringUtils.isNotEmpty(invitationBlock)) {

            invitation.setInvitationBlock(invitationBlock);

            String decodedBlock = decodeInvitationBlock(invitationBlock);
            if (StringUtils.isNotEmpty(decodedBlock)) {
                Map<String, Object> o = null;
                try {
                    o = mapper.readValue(decodedBlock, HashMap.class);
                    invitation.setInvitation(o);
                    invitation.setParsed(true);

                    if ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation".equals(o.get("@type"))) {
                        // Invitation
                        Gson gson = new Gson();
                        ReceiveInvitationRequest r = gson.fromJson(decodedBlock, ReceiveInvitationRequest.class);
                        invitation.setInvitationRequest(r);

                    } else if ("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/out-of-band/1.0/invitation"
                            .equals(o.get("@type"))) {
                        invitation.setOob(true);
                        // not supporting this until we can parse and send to aries client successfully
                        String msg = "Out of band Invitations are currently not supported";
                        invitation.setError(msg);
                        log.error(msg);
                    } else {
                        String msg = String.format("Unknown or unsupported Invitation type. @type = '%s'",
                                o.get("@type"));
                        invitation.setError(msg);
                        log.error(msg);
                    }
                } catch (JsonProcessingException e) {
                    String msg = String.format("Error parsing invitation %s", e.getMessage());
                    invitation.setError(msg);
                    log.error(msg, e);
                }
            } else {
                String msg = "Invitation could not be decoded; result was empty";
                invitation.setError(msg);
                log.error(msg);
            }
        } else {
            String msg = "Invitation was empty";
            invitation.setError(msg);
            log.error(msg);
        }
        return invitation;
    }

    private String parseInvitationBlock(@NonNull HttpUrl url) {
        List<String> paramNames = List.of("c_i", "d_m", "oob");
        for (String name : paramNames) {
            String invitationBlock = url.queryParameter(name);
            if (StringUtils.isNotEmpty(invitationBlock))
                return invitationBlock;
        }
        return null;
    }

    private String decodeInvitationBlock(String invitationBlock) {
        if (StringUtils.isNotEmpty(invitationBlock)) {
            byte[] decodedBlockBytes = Base64.getDecoder().decode(invitationBlock);
            return new String(decodedBlockBytes, StandardCharsets.UTF_8);
        }
        return null;
    }

    private String parseInvitationBlockFromRedirect(@NonNull HttpUrl url) {
        // in this case, we are going to get the url and see if we can get a redirect to
        // another url that contains invitation
        try {
            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isRedirect()) {
                    String location = response.header("location");
                    if (StringUtils.isNotEmpty(location)) {
                        HttpUrl locationUrl = HttpUrl.parse(location);
                        if (locationUrl != null)
                            return parseInvitationBlock(locationUrl);
                    }
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

}
