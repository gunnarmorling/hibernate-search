/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.test.backend.elasticsearch;

import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.test.InternalTestCluster;

import org.junit.rules.TemporaryFolder;

/**
 * A simple JUnit TestRule which starts an im-JVM ElasticSearch instance for testing purposes.
 *
 * @author Sanne Grinovero
 */
public class ESRuntime extends TemporaryFolder {

	private Node node;

	@Override
	protected void before() throws Throwable {
		super.before();
		// Inspired by org.elasticsearch.test.ESSingleNodeTestCase :
		Node build = NodeBuilder.nodeBuilder().local( true ).data( true ).settings( Settings.builder()
				.put( ClusterName.SETTING, InternalTestCluster.clusterName( "one-node-cluster", 13L ) ) //13L is a random number ;)
				.put( "path.home", newFolder( "es-home" ) )
				.put( "path.shared_data", newFolder( "es-shareddata" ) )
				.put( "node.name", "es-1" )
				.put( IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1 )
				.put( IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0 )
				.put( EsExecutors.PROCESSORS, 1) // limit the number of threads created
				.put( "http.enabled", true )
				.put( InternalSettingsPreparer.IGNORE_SYSTEM_PROPERTIES_SETTING, true )
			).build();
		node = build.start();
	}

	@Override
	protected void after() {
		try {
			node.close();
		}
		finally {
			super.after();
		}
	}

}
