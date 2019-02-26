/*
 * Copyright (C) 2018 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
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
package de.fraunhofer.iosb.ilt.sta.model.builder.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.fraunhofer.iosb.ilt.sta.model.core.NamedEntity;

/**
 * @author scf
 * @param <U> Type of class to build.
 * @param <V> Type of the builder class (will be recursive)
 */
public abstract class NamedEntityBuilder<U extends NamedEntity<U>, V extends AbstractEntityBuilder<U, V>> extends AbstractEntityBuilder<U, V> {

    private String name;
    private String description;
    private ObjectNode properties;

    public NamedEntityBuilder() {
        properties = JsonNodeFactory.instance.objectNode();
    }

    public final V setName(String name) {
        this.name = name;
        return getThis();
    }

    public final V setDescription(String description) {
        this.description = description;
        return getThis();
    }

    public final V setProperties(ObjectNode properties) {
        this.properties = properties;
        return getThis();
    }

    public final V addProperty(String name, JsonNode value) {
        this.properties.set(name, value);
        return getThis();
    }

    public final V addProperty(String name, String value) {
        this.properties.put(name, value);
        return getThis();
    }

    public final V addProperty(String name, int value) {
        this.properties.put(name, value);
        return getThis();
    }

    public final V addProperty(String name, double value) {
        this.properties.put(name, value);
        return getThis();
    }

    public final V addProperty(String name, boolean value) {
        this.properties.put(name, value);
        return getThis();
    }

    @Override
    protected U build(U entity) {
        super.build(entity);
        entity.setName(name);
        entity.setDescription(description);
        entity.setProperties(properties);
        return entity;
    }

}
