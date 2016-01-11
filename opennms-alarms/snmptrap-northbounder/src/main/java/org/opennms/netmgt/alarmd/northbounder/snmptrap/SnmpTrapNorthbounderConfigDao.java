/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2012-2014 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2014 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.alarmd.northbounder.snmptrap;

import java.io.File;

import org.opennms.core.utils.ConfigFileConstants;
import org.opennms.core.xml.AbstractJaxbConfigDao;
import org.opennms.core.xml.JaxbUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class SnmpTrapNorthbounderConfigDao.
 * 
 * @author <a href="mailto:agalue@opennms.org">Alejandro Galue</a>
 */
public class SnmpTrapNorthbounderConfigDao extends AbstractJaxbConfigDao<SnmpTrapNorthbounderConfig, SnmpTrapNorthbounderConfig> {

    /** The Constant LOG. */
    public static final Logger LOG = LoggerFactory.getLogger(SnmpTrapNorthbounderConfigDao.class);

    /** The configuration directory. */
    private File m_configDirectory;

    /**
     * Instantiates a new SNMP Trap northbounder configuration DAO.
     */
    public SnmpTrapNorthbounderConfigDao() {
        super(SnmpTrapNorthbounderConfig.class, "Config for SNMP Trap Northbounder");
    }

    /* (non-Javadoc)
     * @see org.opennms.core.xml.AbstractJaxbConfigDao#translateConfig(java.lang.Object)
     */
    @Override
    protected SnmpTrapNorthbounderConfig translateConfig(SnmpTrapNorthbounderConfig config) {
        for (SnmpTrapSink sink : config.getSnmpTrapSinks()) {
            if (sink.getImportMappings() != null) {
                for (String link : sink.getImportMappings()) {
                    File configFile = new File(getConfigDirectory(), link);
                    if (configFile.exists()) {
                        try {
                            LOG.debug("Parsing file {}", configFile);
                            SnmpTrapMappingGroup group = JaxbUtils.unmarshal(SnmpTrapMappingGroup.class, configFile);
                            sink.getMappings().add(group);
                        } catch (Exception e) {
                            LOG.error("Can't parse {}", link, e);
                        }
                    }
                }
            }
        }
        return config;
    }

    /**
     * Gets the configuration directory.
     *
     * @return the configuration directory
     */
    private File getConfigDirectory() {
        if (m_configDirectory == null) {
            final StringBuffer sb = new StringBuffer(ConfigFileConstants.getHome());
            sb.append(File.separator);
            sb.append("etc");
            sb.append(File.separator);
            m_configDirectory = new File(sb.toString());
        }
        return m_configDirectory;
    }

    /**
     * Gets the SNMP Trap northbounder configuration.
     *
     * @return the configuration object
     */
    public SnmpTrapNorthbounderConfig getConfig() {
        return getContainer().getObject();
    }

    /**
     * Gets the SNMP Trap sinks.
     * 
     * @param snmpTrapSinkName the SNMP Trap sink name
     * @return the SNMP Trap sink
     */
    public SnmpTrapSink getSnmpTrapSink(String snmpTrapSinkName) {
        for (SnmpTrapSink dest : getConfig().getSnmpTrapSinks()) {
            if (dest.getName().equals(snmpTrapSinkName)) {
                return dest;
            }
        }
        return null;
    }

    /**
     * Reload.
     */
    public void reload() {
        getContainer().reload();
    }
}
