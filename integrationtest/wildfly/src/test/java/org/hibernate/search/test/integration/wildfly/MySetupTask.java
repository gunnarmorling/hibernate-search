/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.test.integration.wildfly;

import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.dmr.ModelNode;


/**
 * @author Gunnar Morling
 *
 */
public class MySetupTask implements ServerSetupTask {

	@Override
	public void setup(ManagementClient managementClient, String containerId) throws Exception {
		managementClient.getControllerClient().execute( new ModelNode() );
//		managementClient.getControllerClient().execute( arg0 )
	}

	@Override
	public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
	}
}
