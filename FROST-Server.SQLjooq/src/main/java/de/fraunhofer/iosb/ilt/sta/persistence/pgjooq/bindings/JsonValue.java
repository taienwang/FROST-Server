/*
 * Copyright (C) 2019 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.sta.persistence.pgjooq.bindings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.fraunhofer.iosb.ilt.sta.persistence.pgjooq.Utils;

/**
 *
 * @author scf
 */
public class JsonValue {

    private JsonNode value;
    private int stringLength = 0;

    public JsonValue(String stringValue) {
        if (stringValue != null) {
            stringLength = stringValue.length();
            value = Utils.jsonToTree(stringValue);
        }
    }

    public JsonValue(JsonNode value) {
        this.value = value;
    }

    public JsonValue(Object value) {
        this.value = Utils.objectToTree(value);
    }

    public JsonNode getValue() {
        return value;
    }

    public ObjectNode getObjectValue() {
        if (value == null || value.isObject()) {
            return (ObjectNode) value;
        }
        throw new IllegalArgumentException("Non-object json node found");
    }

    public void setValue(JsonNode value) {
        this.value = value;
    }

    public int getStringLength() {
        return stringLength;
    }

}
