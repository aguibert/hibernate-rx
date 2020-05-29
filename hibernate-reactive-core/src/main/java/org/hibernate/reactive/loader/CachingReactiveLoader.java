/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.loader;

import org.hibernate.HibernateException;
import org.hibernate.JDBCException;
import org.hibernate.LockOptions;
import org.hibernate.QueryException;
import org.hibernate.cache.spi.FilterKey;
import org.hibernate.cache.spi.QueryKey;
import org.hibernate.cache.spi.QueryResultsCache;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.engine.spi.RowSelection;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.CoreLogging;
import org.hibernate.internal.CoreMessageLogger;
import org.hibernate.loader.Loader;
import org.hibernate.reactive.adaptor.impl.PreparedStatementAdaptor;
import org.hibernate.reactive.util.impl.CompletionStages;
import org.hibernate.stat.spi.StatisticsImplementor;
import org.hibernate.transform.CacheableResultTransformer;
import org.hibernate.transform.ResultTransformer;
import org.hibernate.type.Type;

import java.io.Serializable;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Defines common reactive operations inherited by query loaders, in
 * particular, interaction with the cache.
 *
 * @see org.hibernate.loader.Loader
 *
 * @author Gavin King
 */
public interface CachingReactiveLoader extends ReactiveLoader {

	CoreMessageLogger log = CoreLogging.messageLogger( Loader.class );

	default CompletionStage<List<Object>> doReactiveList(
			final String sql, final String queryIdentifier,
			final SessionImplementor session,
			final QueryParameters queryParameters,
			final ResultTransformer forcedResultTransformer)
			throws HibernateException {

		final StatisticsImplementor statistics = session.getSessionFactory().getStatistics();
		final boolean stats = statistics.isStatisticsEnabled();
		final long startTime = stats ? System.nanoTime() : 0;

		return doReactiveQueryAndInitializeNonLazyCollections( sql, session, queryParameters, true, forcedResultTransformer )
				.handle( (list, e ) -> {
					CompletionStages.convertSqlException( e, session, () -> "could not execute query", sql );

					if ( e ==null && stats ) {
						final long endTime = System.nanoTime();
						final long milliseconds = TimeUnit.MILLISECONDS.convert( endTime - startTime, TimeUnit.NANOSECONDS );
						statistics.queryExecuted( queryIdentifier, list.size(), milliseconds );
					}

					return CompletionStages.returnOrRethrow(e, list );
				} );
	}

	default CompletionStage<List<Object>> reactiveListIgnoreQueryCache(
			String sql, String queryIdentifier,
			SharedSessionContractImplementor session,
			QueryParameters queryParameters) {
		return doReactiveList( sql, queryIdentifier, (SessionImplementor) session, queryParameters, null )
				.thenApply( result -> getResultList( result, queryParameters.getResultTransformer() ) );
	}

	default CompletionStage<List<Object>> reactiveListUsingQueryCache(
			final String sql, final String queryIdentifier,
			final SessionImplementor session,
			final QueryParameters queryParameters,
			final Set<Serializable> querySpaces,
			final Type[] resultTypes) {

		QueryResultsCache queryCache = session.getSessionFactory().getCache()
				.getQueryResultsCache( queryParameters.getCacheRegion() );

		QueryKey key = queryKey( sql, session, queryParameters );

		List<Object> cachedList = getResultFromQueryCache( session, queryParameters, querySpaces, resultTypes, queryCache, key );

		CompletionStage<List<Object>> list;
		if ( cachedList == null ) {
			list = doReactiveList( sql, queryIdentifier, session, queryParameters, key.getResultTransformer() )
					.thenApply( cachableList -> {
						putResultInQueryCache( session, queryParameters, resultTypes, queryCache, key, cachableList );
						return cachableList;
					} );
		}
		else {
			list = CompletionStages.completedFuture( cachedList );
		}

		return list.thenApply(
				result -> getResultList(
						transform( queryParameters, key, result,
								resolveResultTransformer( queryParameters.getResultTransformer() ) ),
						queryParameters.getResultTransformer()
				)
		);
	}

	default List<?> transform(QueryParameters queryParameters, QueryKey key, List<Object> result,
							  ResultTransformer resolvedTransformer) {
		if (resolvedTransformer == null) {
			return result;
		}
		else {
			CacheableResultTransformer transformer = key.getResultTransformer();
			if ( areResultSetRowsTransformedImmediately() ) {
				return transformer.retransformResults(
						result,
						getResultRowAliases(),
						queryParameters.getResultTransformer(),
						includeInResultRow()
				);
			}
			else {
				return transformer.untransformToTuples(result);
			}
		}
	}

	default QueryKey queryKey(String sql, SessionImplementor session, QueryParameters queryParameters) {
		return QueryKey.generateQueryKey(
				sql,
				queryParameters,
				FilterKey.createFilterKeys( session.getLoadQueryInfluencers().getEnabledFilters() ),
				session,
				cacheableResultTransformer( queryParameters )
		);
	}

	default CacheableResultTransformer cacheableResultTransformer(QueryParameters queryParameters) {
		return CacheableResultTransformer.create(
				queryParameters.getResultTransformer(),
				getResultRowAliases(),
				includeInResultRow()
		);
	}

	boolean[] includeInResultRow();

	List<Object> getResultFromQueryCache(SessionImplementor session, QueryParameters queryParameters, Set<Serializable> querySpaces, Type[] resultTypes, QueryResultsCache queryCache, QueryKey key);

	void putResultInQueryCache(SessionImplementor session, QueryParameters queryParameters, Type[] resultTypes, QueryResultsCache queryCache, QueryKey key, List<Object> cachableList);

	ResultTransformer resolveResultTransformer(ResultTransformer resultTransformer);

	String[] getResultRowAliases();

	boolean areResultSetRowsTransformedImmediately();

	List<Object> getResultList(List<?> results, ResultTransformer resultTransformer) throws QueryException;

	@Override
	default Object[] toParameterArray(QueryParameters queryParameters, SharedSessionContractImplementor session) {
		PreparedStatementAdaptor adaptor = new PreparedStatementAdaptor();
		try {
			bindStatement(
					adaptor,
					queryParameters,
					limitHandler( queryParameters.getRowSelection(), session ),
					session
			);
			return adaptor.getParametersAsArray();
		}
		catch (SQLException e) {
			//can never happen
			throw new JDBCException("error binding parameters", e);
		}
	}

	/**
	 * This is based on the code related to binding a PreparedStatement in {@link Loader#prepareQueryStatement},
	 * with modifications.
	 */
	default PreparedStatement bindStatement(
			final PreparedStatement st,
			final QueryParameters queryParameters,
			final LimitHandler limitHandler,
			final SharedSessionContractImplementor session) throws SQLException, HibernateException {

		final Dialect dialect = session.getFactory().getJdbcServices().getDialect();
		final RowSelection selection = queryParameters.getRowSelection();
		final boolean callable = queryParameters.isCallable();

		int col = 1;
		//TODO: can we limit stored procedures ?!
		col += limitHandler.bindLimitParametersAtStartOfQuery( selection, st, col );

		if ( callable ) {
			col = dialect.registerResultSetOutParameter( (CallableStatement) st, col );
		}

		col += bindParameterValues( st, queryParameters, col, session );

		col += limitHandler.bindLimitParametersAtEndOfQuery( selection, st, col );

		limitHandler.setMaxRows( selection, st );

		// no support for these options in Reactive
//		if ( selection != null ) {
//			if ( selection.getTimeout() != null ) {
//				st.setQueryTimeout( selection.getTimeout() );
//			}
//			if ( selection.getFetchSize() != null ) {
//				st.setFetchSize( selection.getFetchSize() );
//			}
//		}

		// handle lock timeout...
		LockOptions lockOptions = queryParameters.getLockOptions();
		if ( lockOptions != null ) {
			if ( lockOptions.getTimeOut() != LockOptions.WAIT_FOREVER ) {
				if ( !dialect.supportsLockTimeouts() ) {
					if ( log.isDebugEnabled() ) {
						log.debugf(
								"Lock timeout [%s] requested but dialect reported to not support lock timeouts",
								lockOptions.getTimeOut()
						);
					}
				}
				else if ( dialect.isLockTimeoutParameterized() ) {
					st.setInt( col++, lockOptions.getTimeOut() );
				}
			}
		}

		if ( log.isTraceEnabled() ) {
			log.tracev( "Bound [{0}] parameters total", col );
		}

		return st;
	}

	int bindParameterValues(
			final PreparedStatement statement,
			final QueryParameters queryParameters,
			final int startIndex,
			final SharedSessionContractImplementor session) throws SQLException;
}