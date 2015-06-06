/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.agent.monitor.inventory;

public abstract class ConfigurationPropertyInstance<T extends ConfigurationPropertyType> extends NamedObject {

    public ConfigurationPropertyInstance(ID id, Name name, T configurationPropertyType) {
        super(id, name);
        this.configurationPropertyType = configurationPropertyType;
    }

    private static final String VALUE_PROP = "value";
    private final T configurationPropertyType;

    public T getConfigurationPropertyType() {
        return configurationPropertyType;
    }

    public String getValue() {
        return (String) getProperties().get(VALUE_PROP);
    }

    public void setValue(String value) {
        getProperties().put(VALUE_PROP, value);
    }
}
