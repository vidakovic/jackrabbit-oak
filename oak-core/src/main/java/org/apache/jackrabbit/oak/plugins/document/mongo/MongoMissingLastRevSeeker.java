/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.document.mongo;

import static com.google.common.collect.Iterables.transform;

import com.google.common.base.Function;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import com.mongodb.ReadPreference;

import org.apache.jackrabbit.oak.plugins.document.ClusterNodeInfoDocument;
import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.Commit;
import org.apache.jackrabbit.oak.plugins.document.MissingLastRevSeeker;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.util.CloseableIterable;

/**
 * Mongo specific version of MissingLastRevSeeker which uses mongo queries
 * to fetch candidates which may have missed '_lastRev' updates.
 * 
 * Uses a time range to find documents modified during that interval.
 */
public class MongoMissingLastRevSeeker extends MissingLastRevSeeker {
    private final MongoDocumentStore store;

    public MongoMissingLastRevSeeker(MongoDocumentStore store) {
        super(store);
        this.store = store;
    }

    @Override
    public ClusterNodeInfoDocument getClusterNodeInfo(final int clusterId) {
        DBObject query =
                QueryBuilder
                        .start(NodeDocument.ID).is(String.valueOf(clusterId)).get();
        DBCursor cursor =
                getClusterNodeCollection().find(query)
                        .setReadPreference(ReadPreference.secondaryPreferred());
        try {
            if (cursor.hasNext()) {
                DBObject obj = cursor.next();
                return store.convertFromDBObject(Collection.CLUSTER_NODES, obj);
            }
        } finally {
            cursor.close();
        }
        return null;
    }

    @Override
    public CloseableIterable<NodeDocument> getCandidates(final long startTime,
            final long endTime) {
        DBObject query =
                QueryBuilder
                        .start(NodeDocument.MODIFIED_IN_SECS).lessThanEquals(
                                Commit.getModifiedInSecs(endTime))
                        .put(NodeDocument.MODIFIED_IN_SECS).greaterThanEquals(
                                Commit.getModifiedInSecs(startTime))
                        .get();
        DBObject sortFields = new BasicDBObject(NodeDocument.MODIFIED_IN_SECS, -1);

        DBCursor cursor =
                getNodeCollection().find(query)
                        .sort(sortFields)
                        .setReadPreference(
                                ReadPreference.secondaryPreferred());
        return CloseableIterable.wrap(transform(cursor, new Function<DBObject, NodeDocument>() {
            @Override
            public NodeDocument apply(DBObject input) {
                return store.convertFromDBObject(Collection.NODES, input);
            }
        }), cursor);
    }

    private DBCollection getNodeCollection() {
        return store.getDBCollection(Collection.NODES);
    }

    private DBCollection getClusterNodeCollection() {
        return store.getDBCollection(Collection.CLUSTER_NODES);
    }
}
