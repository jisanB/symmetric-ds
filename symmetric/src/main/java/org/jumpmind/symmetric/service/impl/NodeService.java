/*
 * SymmetricDS is an open source database synchronization solution.
 *   
 * Copyright (C) Eric Long <erilong@users.sourceforge.net>,
 *               Andrew Wilcox <andrewbwilcox@users.sourceforge.net>,
 *               Chris Henson <chenson42@users.sourceforge.net>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 */

package org.jumpmind.symmetric.service.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.random.RandomDataImpl;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.ext.INodeIdGenerator;
import org.jumpmind.symmetric.model.DataEventAction;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.NodeSecurity;
import org.jumpmind.symmetric.model.NodeStatus;
import org.jumpmind.symmetric.service.INodeService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

public class NodeService extends AbstractService implements INodeService {

    @SuppressWarnings("unused")
    private static final Log logger = LogFactory.getLog(NodeService.class);

    private Node cachedNodeIdentity;

    private Map<String, NodeSecurity> securityCache;

    private long securityCacheTime;

    /**
     * This is the default implementation of the node generator
     */
    private INodeIdGenerator nodeIdGenerator = new DefaultNodeIdGenerator();

    public String findSymmetricVersion() {
        try {
            return (String) jdbcTemplate.queryForObject(getSql("findSymmetricVersionSql"), String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public String findMyNodeId() {
        try {
            return (String) jdbcTemplate.queryForObject(getSql("findMyNodeIdSql"), String.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Set<Node> findNodesThatOriginatedFromNodeId(String originalNodeId) {
        Set<Node> all = new HashSet<Node>();
        List<Node> list = jdbcTemplate.query(String.format("%s%s", getSql("selectNodePrefixSql"),
                getSql("findNodesCreatedByMeSql")), new Object[] { originalNodeId }, new NodeRowMapper());
        if (list.size() > 0) {
            all.addAll(list);
            for (Node node : list) {
                all.addAll(findNodesThatOriginatedFromNodeId(node.getNodeId()));
            }
        }
        return all;
    }

    /**
     * Lookup a node in the database, which contains information for syncing
     * with it.
     */
    @SuppressWarnings("unchecked")
    public Node findNode(String id) {
        List<Node> list = jdbcTemplate.query(getSql("selectNodePrefixSql") + getSql("findNodeSql"),
                new Object[] { id }, new NodeRowMapper());
        return (Node) getFirstEntry(list);
    }

    @SuppressWarnings("unchecked")
    public Node findNodeByExternalId(String nodeGroupId, String externalId) {
        List<Node> list = jdbcTemplate.query(getSql("selectNodePrefixSql") + getSql("findNodeByExternalIdSql"),
                new Object[] { nodeGroupId, externalId }, new NodeRowMapper());
        return (Node) getFirstEntry(list);
    }

    public void ignoreNodeChannelForExternalId(boolean enabled, String channelId, String nodeGroupId, String externalId) {
        Node node = findNodeByExternalId(nodeGroupId, externalId);
        if (jdbcTemplate.update(getSql("nodeChannelControlIgnoreSql"), new Object[] { enabled ? 1 : 0,
                node.getNodeId(), channelId }) == 0) {
            jdbcTemplate.update(getSql("insertNodeChannelControlSql"), new Object[] { node.getNodeId(), channelId,
                    enabled ? 1 : 0, 0 });
        }
    }

    public boolean isRegistrationEnabled(String nodeId) {
        NodeSecurity nodeSecurity = findNodeSecurity(nodeId);
        if (nodeSecurity != null) {
            return nodeSecurity.isRegistrationEnabled();
        }
        return false;
    }

    /**
     * Lookup a node_security in the database, which contains private
     * information used to authenticate.
     */
    public NodeSecurity findNodeSecurity(String id) {
        return findNodeSecurity(id, false);
    }

    @SuppressWarnings("unchecked")
    public NodeSecurity findNodeSecurity(String id, boolean createIfNotFound) {
        List<NodeSecurity> list = jdbcTemplate.query(getSql("findNodeSecuritySql"), new Object[] { id },
                new NodeSecurityRowMapper());
        NodeSecurity security = (NodeSecurity) getFirstEntry(list);
        if (security == null && createIfNotFound) {
            insertNodeSecurity(id);
            security = findNodeSecurity(id, false);
        }
        return security;
    }

    public void insertNodeSecurity(String id) {
        flushNodeAuthorizedCache();
        jdbcTemplate.update(getSql("insertNodeSecuritySql"), new Object[] { id,
                nodeIdGenerator.generatePassword(new Node(id, null, null)), findIdentity().getNodeId() });
    }

    public boolean updateNode(Node node) {
        boolean updated = jdbcTemplate.update(getSql("updateNodeSql"), new Object[] { node.getNodeGroupId(),
                node.getExternalId(), node.getDatabaseType(), node.getDatabaseVersion(), node.getSchemaVersion(),
                node.getSymmetricVersion(), node.getSyncURL(), node.getHeartbeatTime(), node.isSyncEnabled() ? 1 : 0,
                node.getTimezoneOffset(), node.getCreatedByNodeId(), node.getNodeId() }, new int[] { Types.VARCHAR,
                Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                Types.TIMESTAMP, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR }) == 1;
        return updated;
    }

    protected <T> T getFirstEntry(List<T> list) {
        if (list != null && list.size() > 0) {
            return list.get(0);
        }
        return null;
    }

    /**
     * Check that the given node and password match in the node_security table.
     * A node must authenticate before it's allowed to sync data.
     */
    @SuppressWarnings("unchecked")
    public boolean isNodeAuthorized(String id, String password) {
        long maxSecurityCacheTime = parameterService
                .getLong(ParameterConstants.NODE_SECURITY_CACHE_REFRESH_PERIOD_IN_MS);
        if (System.currentTimeMillis() - securityCacheTime >= maxSecurityCacheTime || securityCacheTime == 0) {
            securityCache = (Map<String, NodeSecurity>) jdbcTemplate.query(getSql("findAllNodeSecuritySql"),
                    new NodeSecurityResultSetExtractor());
            securityCacheTime = System.currentTimeMillis();
        }

        NodeSecurity nodeSecurity = securityCache.get(id);
        if (nodeSecurity != null
                && ((nodeSecurity.getPassword() != null && !nodeSecurity.getPassword().equals("") && nodeSecurity
                        .getPassword().equals(password)) || nodeSecurity.isRegistrationEnabled())) {
            return true;
        }
        return false;
    }

    public void flushNodeAuthorizedCache() {
        securityCacheTime = 0;
    }

    public Node findIdentity() {
        return findIdentity(true);
    }

    @SuppressWarnings("unchecked")
    public Node findIdentity(boolean useCache) {
        if (cachedNodeIdentity == null || useCache == false) {
            List<Node> list = jdbcTemplate.query(getSql("selectNodePrefixSql") + getSql("findNodeIdentitySql"),
                    new NodeRowMapper());
            cachedNodeIdentity = (Node) getFirstEntry(list);
        }
        return cachedNodeIdentity;
    }

    public List<Node> findNodesToPull() {
        return findSourceNodesFor(DataEventAction.WAIT_FOR_PULL);
    }

    public List<Node> findNodesToPushTo() {
        return findTargetNodesFor(DataEventAction.PUSH);
    }

    @SuppressWarnings("unchecked")
    public List<Node> findSourceNodesFor(DataEventAction eventAction) {
        Node node = findIdentity();
        if (node != null) {
            return jdbcTemplate.query(getSql("selectNodePrefixSql") + getSql("findNodesWhoTargetMeSql"), new Object[] {
                    node.getNodeGroupId(), eventAction.getCode() }, new NodeRowMapper());
        } else {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Node> findTargetNodesFor(DataEventAction eventAction) {
        Node node = findIdentity();
        if (node != null) {
            return jdbcTemplate.query(getSql("selectNodePrefixSql") + getSql("findNodesWhoITargetSql"), new Object[] {
                    node.getNodeGroupId(), eventAction.getCode() }, new NodeRowMapper());
        } else {
            return Collections.emptyList();
        }
    }

    public boolean updateNodeSecurity(NodeSecurity security) {
        flushNodeAuthorizedCache();
        return jdbcTemplate.update(getSql("updateNodeSecuritySql"), new Object[] { security.getPassword(),
                security.isRegistrationEnabled() ? 1 : 0, security.getRegistrationTime(),
                security.isInitialLoadEnabled() ? 1 : 0, security.getInitialLoadTime(), security.getCreatedByNodeId(),
                security.getNodeId() }, new int[] { Types.VARCHAR, Types.INTEGER, Types.TIMESTAMP, Types.INTEGER,
                Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR }) == 1;
    }

    public boolean setInitialLoadEnabled(String nodeId, boolean initialLoadEnabled) {
        NodeSecurity nodeSecurity = findNodeSecurity(nodeId, true);
        if (nodeSecurity != null) {
            nodeSecurity.setInitialLoadEnabled(initialLoadEnabled);
            if (initialLoadEnabled) {
                nodeSecurity.setInitialLoadTime(null);
            } else {
                nodeSecurity.setInitialLoadTime(new Date());
            }
            return updateNodeSecurity(nodeSecurity);
        }
        return false;
    }

    class NodeRowMapper implements RowMapper {
        public Object mapRow(ResultSet rs, int num) throws SQLException {
            Node node = new Node();
            node.setNodeId(rs.getString(1));
            node.setNodeGroupId(rs.getString(2));
            node.setExternalId(rs.getString(3));
            node.setSyncEnabled(rs.getBoolean(4));
            node.setSyncURL(rs.getString(5));
            node.setSchemaVersion(rs.getString(6));
            node.setDatabaseType(rs.getString(7));
            node.setDatabaseVersion(rs.getString(8));
            node.setSymmetricVersion(rs.getString(9));
            node.setCreatedByNodeId(rs.getString(10));
            return node;
        }
    }

    class NodeSecurityRowMapper implements RowMapper {
        public Object mapRow(ResultSet rs, int num) throws SQLException {
            NodeSecurity nodeSecurity = new NodeSecurity();
            nodeSecurity.setNodeId(rs.getString(1));
            nodeSecurity.setPassword(rs.getString(2));
            nodeSecurity.setRegistrationEnabled(rs.getBoolean(3));
            nodeSecurity.setRegistrationTime(rs.getTimestamp(4));
            nodeSecurity.setInitialLoadEnabled(rs.getBoolean(5));
            nodeSecurity.setInitialLoadTime(rs.getTimestamp(6));
            nodeSecurity.setCreatedByNodeId(rs.getString(7));
            return nodeSecurity;
        }
    }

    class NodeSecurityResultSetExtractor implements ResultSetExtractor {
        public Object extractData(ResultSet rs) throws SQLException, DataAccessException {
            Map<String, NodeSecurity> result = new HashMap<String, NodeSecurity>();
            NodeSecurityRowMapper mapper = new NodeSecurityRowMapper();

            while (rs.next()) {
                NodeSecurity nodeSecurity = (NodeSecurity) mapper.mapRow(rs, 0);
                result.put(nodeSecurity.getNodeId(), nodeSecurity);
            }
            return result;
        }
    }

    public boolean isExternalIdRegistered(String nodeGroupId, String externalId) {
        return jdbcTemplate.queryForInt(getSql("isNodeRegisteredSql"), new Object[] { nodeGroupId, externalId }) > 0;
    }

    public INodeIdGenerator getNodeIdGenerator() {
        return nodeIdGenerator;
    }

    public void setNodeIdGenerator(INodeIdGenerator nodeIdGenerator) {
        this.nodeIdGenerator = nodeIdGenerator;
    }

    class DefaultNodeIdGenerator implements INodeIdGenerator {

        public boolean isAutoRegister() {
            return true;
        }

        public String selectNodeId(Node node) {
            if (StringUtils.isBlank(node.getNodeId())) {
                String nodeId = node.getExternalId();
                int maxTries = 100;
                for (int sequence = 0; sequence < maxTries; sequence++) {
                    NodeSecurity security = findNodeSecurity(nodeId);
                    if (security != null && security.isRegistrationEnabled()) {
                        return nodeId;
                    }
                    nodeId = node.getExternalId() + "-" + sequence;
                }
            }
            return node.getNodeId();
        }

        public String generateNodeId(Node node) {
            if (StringUtils.isBlank(node.getNodeId())) {
                String nodeId = node.getExternalId();
                int maxTries = 100;
                for (int sequence = 0; sequence < maxTries; sequence++) {
                    if (findNode(nodeId) == null) {
                        return nodeId;
                    }
                    nodeId = node.getExternalId() + "-" + sequence;
                }
                throw new RuntimeException("Could not find nodeId for externalId of " + node.getExternalId()
                        + " after " + maxTries + " tries.");
            } else {
                return node.getNodeId();
            }
        }

        public String generatePassword(Node node) {
            return new RandomDataImpl().nextSecureHexString(30);
        }
    };
    
    public boolean isDataLoadComplete() {
        return getDataLoadStatus() == NodeStatus.DATA_LOAD_COMPLETED;
    }

    public boolean isDataLoadStarted() {
        return getDataLoadStatus() == NodeStatus.DATA_LOAD_STARTED;
    }

    @SuppressWarnings("unchecked")
    private NodeStatus getDataLoadStatus() {

        class DataLoadStatus {
            int initialLoadEnabled;
            Date initialLoadTime;
        }

        List<DataLoadStatus> results = jdbcTemplate.query(getSql("getDataLoadStatusSql"), new RowMapper() {
            public Object mapRow(java.sql.ResultSet rs, int arg1) throws java.sql.SQLException {
                DataLoadStatus status = new DataLoadStatus();
                status.initialLoadEnabled = rs.getInt(1);
                status.initialLoadTime = rs.getTimestamp(2);
                return status;
            }
        });

        if (results.size() > 0) {
            if (results.get(0).initialLoadEnabled == 0) {
                return NodeStatus.DATA_LOAD_NOT_STARTED;
            } else {
                if (results.get(0).initialLoadTime == null) {
                    return NodeStatus.DATA_LOAD_STARTED;
                } else {
                    return NodeStatus.DATA_LOAD_COMPLETED;
                }
            }
        }
        return NodeStatus.DATA_LOAD_NOT_STARTED;
    }
}
