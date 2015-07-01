/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.test.integration.wildfly.massindexing;

import java.io.IOException;

import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.dmr.ModelNode;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UNDEFINE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;

/**
 * @author Gunnar Morling
 *
 */
public class MySetupTask implements ServerSetupTask {

	private Integer originalTimeout = null;

	@Override
	public void setup(ManagementClient managementClient, String containerId) throws Exception {
		ModelNode result = readTransactionTimeout( managementClient );
		originalTimeout = result.has( RESULT ) ? result.get( RESULT ).asInt() : null;

		writeTransactionTimeout( managementClient, 2 );
	}

	@Override
	public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
		if ( originalTimeout != null ) {
			writeTransactionTimeout( managementClient, originalTimeout );
		}
		else {
			undefineTransactionTimeout( managementClient );
		}
	}

	private void writeTransactionTimeout(ManagementClient managementClient, int timeOut) throws IOException {
		ModelNode operation = new ModelNode();
		operation.get( OP ).set( WRITE_ATTRIBUTE_OPERATION );
		operation.get( OP_ADDR ).set( getAddress() );
		operation.get( NAME).set( "default-timeout" );
		operation.get( VALUE ).set( timeOut );
		managementClient.getControllerClient().execute( operation );

		reloadConfiguration( managementClient );
	}

	private void undefineTransactionTimeout(ManagementClient managementClient) throws IOException {
		ModelNode operation = new ModelNode();
		operation.get( OP ).set( UNDEFINE_ATTRIBUTE_OPERATION );
		operation.get( OP_ADDR ).set( getAddress() );
		operation.get( NAME ).set("default-timeout");

		managementClient.getControllerClient().execute( operation );

		reloadConfiguration( managementClient );
	}

	private ModelNode readTransactionTimeout(ManagementClient managementClient) throws IOException {
		ModelNode operation = new ModelNode();
		operation.get( OP ).set( READ_ATTRIBUTE_OPERATION );
		operation.get( OP_ADDR ).set( getAddress() );
		operation.get( NAME ).set( "default-timeout" );

		return managementClient.getControllerClient().execute( operation );
	}

	private void reloadConfiguration(ManagementClient managementClient) throws IOException {
		ModelNode operation = new ModelNode();
		operation.get( OP ).set( "reload" );
		managementClient.getControllerClient().execute( operation );

		for( int i = 0; i < 50; i++ ) {
			try {
				Thread.sleep( 1000 );
				if ( managementClient.isServerInRunningState() ) {
					return;
				}
			} catch (Throwable t) {
				// nothing to do
			}
		}

		throw new RuntimeException("Reloading configuration failed");
	}

	private ModelNode getAddress() {
		ModelNode address = new ModelNode();
		address.add( "subsystem", "transactions" );
		address.protect();
		return address;
	}
}
