/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.test.rest.yaml;

import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Response;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.common.xcontent.XContentType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds an object and allows to extract specific values from it given their path
 */
public class ObjectPath {

    private final Object object;

    public static ObjectPath createFromResponse(Response response) throws IOException {
        byte[] bytes = EntityUtils.toByteArray(response.getEntity());
        String contentType = response.getHeader("Content-Type");
        XContentType xContentType = XContentType.fromMediaType(contentType);
        return ObjectPath.createFromXContent(xContentType.xContent(), new BytesArray(bytes));
    }

    public static ObjectPath createFromXContent(XContent xContent, BytesReference input) throws IOException {
        try (
            XContentParser parser = xContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                input.streamInput()
            )
        ) {
            if (parser.nextToken() == XContentParser.Token.START_ARRAY) {
                return new ObjectPath(parser.listOrderedMap());
            }
            return new ObjectPath(parser.mapOrdered());
        }
    }

    public ObjectPath(Object object) {
        this.object = object;
    }

    /**
     * A utility method that creates an {@link ObjectPath} via {@link #ObjectPath(Object)} returns
     * the result of calling {@link #evaluate(String)} on it.
     */
    public static <T> T evaluate(Object object, String path) throws IOException {
        return new ObjectPath(object).evaluate(path, Stash.EMPTY);
    }

    /**
     * Returns the object corresponding to the provided path if present, null otherwise
     */
    public <T> T evaluate(String path) throws IOException {
        return evaluate(path, Stash.EMPTY);
    }

    /**
     * Returns the object corresponding to the provided path if present, null otherwise
     */
    @SuppressWarnings("unchecked")
    public <T> T evaluate(String path, Stash stash) throws IOException {
        String[] parts = parsePath(path);
        Object object = this.object;
        for (String part : parts) {
            object = evaluate(part, object, stash);
            if (object == null) {
                return null;
            }
        }
        return (T) object;
    }

    @SuppressWarnings("unchecked")
    private Object evaluate(String key, Object object, Stash stash) throws IOException {
        if (stash.containsStashedValue(key)) {
            key = stash.getValue(key).toString();
        }

        if (object instanceof Map) {
            final Map<String, Object> objectAsMap = (Map<String, Object>) object;
            if ("_arbitrary_key_".equals(key)) {
                if (objectAsMap.isEmpty()) {
                    throw new IllegalArgumentException("requested [" + key + "] but the map was empty");
                }
                if (objectAsMap.containsKey(key)) {
                    throw new IllegalArgumentException("requested meta-key [" + key + "] but the map unexpectedly contains this key");
                }
                return objectAsMap.keySet().iterator().next();
            }
            return objectAsMap.get(key);
        }
        if (object instanceof List) {
            List<Object> list = (List<Object>) object;
            try {
                return list.get(Integer.valueOf(key));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("element was a list, but [" + key + "] was not numeric", e);
            } catch (IndexOutOfBoundsException e) {
                throw new IllegalArgumentException(
                    "element was a list with " + list.size() + " elements, but [" + key + "] was out of bounds",
                    e
                );
            }
        }

        throw new IllegalArgumentException("no object found for [" + key + "] within object of class [" + object.getClass() + "]");
    }

    private String[] parsePath(String path) {
        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                escape = true;
                continue;
            }

            if (c == '.') {
                if (escape) {
                    escape = false;
                } else {
                    if (current.length() > 0) {
                        list.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
            }

            current.append(c);
        }

        if (current.length() > 0) {
            list.add(current.toString());
        }

        return list.toArray(new String[0]);
    }

    /**
     * Create a new {@link XContentBuilder} from the xContent object underlying this {@link ObjectPath}.
     * This only works for {@link ObjectPath} instances created from an xContent object, not from nested
     * substructures. We throw an {@link UnsupportedOperationException} in those cases.
     */
    @SuppressWarnings("unchecked")
    public XContentBuilder toXContentBuilder(XContent xContent) throws IOException {
        XContentBuilder builder = XContentBuilder.builder(xContent);
        if (this.object instanceof Map) {
            builder.map((Map<String, Object>) this.object);
        } else {
            throw new UnsupportedOperationException("Only ObjectPath created from a map supported.");
        }
        return builder;
    }
}
