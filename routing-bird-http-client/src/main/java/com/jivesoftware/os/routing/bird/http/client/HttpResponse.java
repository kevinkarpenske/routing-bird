/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.routing.bird.http.client;

public class HttpResponse {

    private final int statusCode;
    private final String statusReasonPhrase;
    private final byte[] responseBody;

    public HttpResponse(int statusCode, String statusReasonPhrase, byte[] responseBody) {
        this.statusCode = statusCode;
        this.statusReasonPhrase = statusReasonPhrase;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getStatusReasonPhrase() {
        return statusReasonPhrase;
    }

    public byte[] getResponseBody() {
        return responseBody;
    }
}
