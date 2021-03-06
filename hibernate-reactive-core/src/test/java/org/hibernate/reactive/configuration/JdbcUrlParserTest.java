/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.configuration;

import org.hibernate.reactive.pool.impl.SqlClientPool;
import org.junit.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

public class JdbcUrlParserTest {

	@Test
	public void returnsNullForNull() {
		URI uri = SqlClientPool.parse( null );
		assertThat( uri ).isNull();
	}

	@Test
	public void uriCreation() {
		URI uri = SqlClientPool.parse("jdbc:postgresql://localhost:5432/hreact");
		assertThat(uri).isNotNull();
	}

	@Test
	public void parsePort() {
		URI uri = SqlClientPool.parse("jdbc:postgresql://localhost:5432/hreact");
		assertThat(uri).hasPort(5432);
	}

	@Test
	public void parseHost() {
		URI uri = SqlClientPool.parse("jdbc:postgresql://localhost:5432/hreact");
		assertThat(uri).hasHost("localhost");
	}

	@Test
	public void parseScheme() {
		URI uri = SqlClientPool.parse("jdbc:postgresql://localhost:5432/hreact");
		assertThat(uri).hasScheme("postgresql");
	}
}
