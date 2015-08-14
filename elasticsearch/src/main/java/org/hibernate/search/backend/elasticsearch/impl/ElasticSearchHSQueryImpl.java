/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.elasticsearch.impl;

import io.searchbox.core.Search;
import io.searchbox.core.SearchResult;
import io.searchbox.core.search.sort.Sort;
import io.searchbox.core.search.sort.Sort.Sorting;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleField;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.FloatField;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.hibernate.search.backend.elasticsearch.ProjectionConstants;
import org.hibernate.search.backend.elasticsearch.client.impl.JestClientReference;
import org.hibernate.search.bridge.FieldBridge;
import org.hibernate.search.bridge.TwoWayFieldBridge;
import org.hibernate.search.engine.integration.impl.ExtendedSearchIntegrator;
import org.hibernate.search.engine.metadata.impl.DocumentFieldMetadata;
import org.hibernate.search.engine.metadata.impl.PropertyMetadata;
import org.hibernate.search.engine.spi.EntityIndexBinding;
import org.hibernate.search.exception.AssertionFailure;
import org.hibernate.search.filter.FullTextFilter;
import org.hibernate.search.indexes.spi.IndexManager;
import org.hibernate.search.query.engine.impl.AbstractHSQuery;
import org.hibernate.search.query.engine.impl.EntityInfoImpl;
import org.hibernate.search.query.engine.impl.TimeoutManagerImpl;
import org.hibernate.search.query.engine.spi.DocumentExtractor;
import org.hibernate.search.query.engine.spi.EntityInfo;
import org.hibernate.search.query.engine.spi.FacetManager;
import org.hibernate.search.query.engine.spi.HSQuery;
import org.hibernate.search.spatial.Coordinates;
import org.hibernate.search.util.logging.impl.Log;
import org.hibernate.search.util.logging.impl.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author gunnar
 *
 */
public class ElasticSearchHSQueryImpl extends AbstractHSQuery {

	private static final Log LOG = LoggerFactory.make();
	private static final Pattern DOT = Pattern.compile( "\\." );

	private final String jsonQuery;

	private TimeoutManagerImpl timeoutManager;

	private Integer resultSize;

	public ElasticSearchHSQueryImpl(String jsonQuery, ExtendedSearchIntegrator extendedIntegrator) {
		super ( extendedIntegrator );
		this.jsonQuery = jsonQuery;
	}

	@Override
	public HSQuery luceneQuery(Query query) {
		throw new UnsupportedOperationException( "Cannot use Lucene query with ElasticSearch" );
	}

	@Override
	public FacetManager getFacetManager() {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public Query getLuceneQuery() {
		throw new UnsupportedOperationException( "Cannot use Lucene query with ElasticSearch" );
	}

	@Override
	public DocumentExtractor queryDocumentExtractor() {
		return new ElasticSearchDocumentExtractor();
	}

	@Override
	public int queryResultSize() {
		if ( resultSize == null ) {
			IndexSearcher searcher = new IndexSearcher();
			SearchResult searchResult = searcher.runSearch();

			resultSize = searchResult.getTotal();
		}

		return resultSize;
	}

	@Override
	public Explanation explain(int documentId) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public HSQuery setSpatialParameters(Coordinates center, String fieldName) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public HSQuery tenantIdentifier(String tenantId) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public HSQuery filter(Filter filter) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public FullTextFilter enableFullTextFilter(String name) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	public void disableFullTextFilter(String name) {
		throw new UnsupportedOperationException( "Not yet implemented" );
	}

	@Override
	protected void clearCachedResults() {
		resultSize = null;
	}

	@Override
	protected TimeoutManagerImpl getTimeoutManagerImpl() {
		if ( timeoutManager == null ) {
			if ( jsonQuery == null ) {
				throw new AssertionFailure( "Requesting TimeoutManager before setting luceneQuery()" );
			}
			timeoutManager = new TimeoutManagerImpl( jsonQuery, timeoutExceptionFactory, this.extendedIntegrator.getTimingSource() );
		}
		return timeoutManager;
	}

	@Override
	public List<EntityInfo> queryEntityInfos() {
		IndexSearcher searcher = new IndexSearcher();
		SearchResult searchResult = searcher.runSearch();

		List<EntityInfo> results = new ArrayList<>( searchResult.getTotal() );
		JsonArray hits = searchResult.getJsonObject().get( "hits" ).getAsJsonObject().get( "hits" ).getAsJsonArray();

		for (JsonElement hit : hits) {
			results.add( searcher.convertQueryHit( hit.getAsJsonObject() ) );
		}

		return results;
	}

	private Object getId(JsonObject hit, EntityIndexBinding binding) {
		Document tmp = new Document();
		tmp.add( new StringField( "id", hit.get( "_id" ).getAsString(), Store.NO) );
		Object id = binding.getDocumentBuilder().getIdBridge().get( "id", tmp );

		return id;
	}

	/**
	 * Determines the affected indexes and runs the given query against them.
	 */
	private class IndexSearcher {

		private final Search search;
		private final Map<String, Class<?>> entityTypesByName;

		private IndexSearcher() {
			Search.Builder search = new Search.Builder( jsonQuery );
			entityTypesByName = new HashMap<>();

			if ( indexedTargetedEntities == null || indexedTargetedEntities.isEmpty() ) {
				for ( Entry<Class<?>, EntityIndexBinding> binding : extendedIntegrator.getIndexBindings().entrySet() ) {
					entityTypesByName.put( binding.getKey().getName(), binding.getKey() );

					IndexManager[] indexManagers = binding.getValue().getIndexManagers();
					for (IndexManager indexManager : indexManagers) {
						ElasticSearchIndexManager esIndexManager = (ElasticSearchIndexManager) indexManager;
						search.addIndex( esIndexManager.getActualIndexName() );
					}
				}
			}
			else {
				for ( Class<?> entityType : indexedTargetedEntities ) {
					entityTypesByName.put( entityType.getName(), entityType );

					EntityIndexBinding binding = extendedIntegrator.getIndexBinding( entityType );
					IndexManager[] indexManagers = binding.getIndexManagers();

					for (IndexManager indexManager : indexManagers) {
						ElasticSearchIndexManager esIndexManager = (ElasticSearchIndexManager) indexManager;
						search.addIndex( esIndexManager.getActualIndexName() );
					}
				}
			}

			search.setParameter( "from", firstResult );
			search.setParameter( "size", maxResults != null ? maxResults : Integer.MAX_VALUE );

			// TODO: Id, embedded fields
			if ( sort != null ) {
				for( SortField sortField : sort.getSort() ) {
					search.addSort( new Sort( sortField.getField(), sortField.getReverse() ? Sorting.DESC : Sorting.ASC ) );
				}
			}
			this.search = search.build();
		}

		SearchResult runSearch() {
			try ( JestClientReference clientReference = new JestClientReference( extendedIntegrator.getServiceManager() ) ) {
				return clientReference.executeRequest( search );
			}
		}

		EntityInfo convertQueryHit(JsonObject hit) {
			String type = hit.get( "_type" ).getAsString();
			Class<?> clazz = entityTypesByName.get( type );
			EntityIndexBinding binding = extendedIntegrator.getIndexBinding( clazz );
			Object id = getId( hit, binding );
			Object[] projections = null;
			List<Integer> indexesOfThis = null;

			if ( projectedFields != null ) {
				projections = new Object[projectedFields.length];
				indexesOfThis = new ArrayList<>();

				int i = 0;
				for( String field : projectedFields ) {
					switch( field ) {
						case ProjectionConstants.SOURCE:
							projections[i] = hit.getAsJsonObject().get( "_source" ).toString();
							break;
						case ProjectionConstants.DOCUMENT:
							throw new IllegalArgumentException( "Projection of Lucene document not supported with ElasticSearch backend" );
						case DOCUMENT_ID:
							throw new IllegalArgumentException( "Projection of Lucene document id not supported with ElasticSearch backend" );
						case ProjectionConstants.EXPLANATION:
							throw new UnsupportedOperationException( "Not yet implemented" );
						case ProjectionConstants.ID:
							projections[i] = id;
							break;
						case ProjectionConstants.OBJECT_CLASS:
							projections[i] = clazz;
							break;
						case ProjectionConstants.SCORE:
							projections[i] = hit.getAsJsonObject().get( "_score" ).getAsFloat();
							break;
						case ProjectionConstants.SPATIAL_DISTANCE:
							throw new UnsupportedOperationException( "Not yet implemented" );
						case ProjectionConstants.THIS:
							indexesOfThis.add( i );
							break;
						default:
							projections[i] = getFieldValue( binding, hit, field );
					}

					i++;
				}
			}

			EntityInfoImpl entityInfo = new EntityInfoImpl( clazz, binding.getDocumentBuilder().getIdentifierName(), (Serializable) id, projections );

			if ( indexesOfThis != null ) {
				entityInfo.getIndexesOfThis().addAll( indexesOfThis );
			}

			return entityInfo;
		}

		/**
		 * Returns the value of the given field as retrieved from the ES result and converted using the corresponding
		 * field bridge. In case this bridge is not a 2-way bridge, the unconverted value will be returned.
		 */
		private Object getFieldValue(EntityIndexBinding binding, JsonObject hit, String projectedField) {
			PropertyMetadata property = FieldHelper.getPropertyMetadata( binding, projectedField );
			DocumentFieldMetadata field = property.getFieldMetadata( projectedField );

			if ( field == null ) {
				throw new IllegalArgumentException( "Unknown field " + projectedField + " for entity "
						+ binding.getDocumentBuilder().getMetadata().getType().getName() );
			}

			FieldBridge fieldBridge = field.getFieldBridge();
			JsonElement value = getFieldValue( hit.get( "_source" ).getAsJsonObject(), projectedField );

			if ( FieldHelper.isBoolean( binding, projectedField ) ) {
				return value.getAsBoolean();
			}
			else if ( FieldHelper.isDate( binding, projectedField ) ) {
				return getAsDate( value.getAsString() );
			}
			else if ( fieldBridge instanceof TwoWayFieldBridge ) {
				Document tmp = new Document();

				if ( field.isNumeric() ) {
					if ( FieldHelper.isInt( binding, projectedField ) ) {
						tmp.add( new IntField( projectedField, value.getAsInt(), Store.NO) );
					}
					else if ( FieldHelper.isLong( binding, projectedField ) ) {
						tmp.add( new LongField( projectedField, value.getAsLong(), Store.NO) );
					}
					else if ( FieldHelper.isFloat( binding, projectedField ) ) {
						tmp.add( new FloatField( projectedField, value.getAsFloat(), Store.NO) );
					}
					else if ( FieldHelper.isDouble( binding, projectedField ) ) {
						tmp.add( new DoubleField( projectedField, value.getAsDouble(), Store.NO) );
					}
				}
				else {
					tmp.add( new StringField( projectedField, value.getAsString(), Store.NO) );
				}

				return ( (TwoWayFieldBridge) fieldBridge ).get( projectedField, tmp );
			}

			return value;
		}

		private JsonElement getFieldValue(JsonObject parent, String projectedField) {
			String field = projectedField;

			if ( projectedField.contains( "." ) ) {
				String[] parts = DOT.split( projectedField );
				field = parts[parts.length - 1];

				for (int i = 0; i < parts.length - 1; i++) {
					parent = parent.get( parts[i] ).getAsJsonObject();
				}
			}

			return parent.getAsJsonObject().get( field );
		}
	}

	// TODO Handle resolution
	private Date getAsDate(String value) {
		Calendar c = DatatypeConverter.parseDateTime( value );
		return c.getTime();
	}

	// TODO: Investigate scrolling API: https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-scroll.html
	private class ElasticSearchDocumentExtractor implements DocumentExtractor {

		private final IndexSearcher searcher;
		private List<EntityInfo> results;

		private ElasticSearchDocumentExtractor() {
			searcher = new IndexSearcher();
		}

		@Override
		public EntityInfo extract(int index) throws IOException {
			if ( results == null ) {
				runSearch();
			}

			return results.get( index );
		}

		@Override
		public int getFirstIndex() {
			return 0;
		}

		@Override
		public int getMaxIndex() {
			if ( results == null ) {
				runSearch();
			}

			return results.size() - 1;
		}

		@Override
		public void close() {
		}

		@Override
		public TopDocs getTopDocs() {
			throw new UnsupportedOperationException( "TopDocs not available with ElasticSearch backend" );
		}

		private void runSearch() {
			SearchResult searchResult = searcher.runSearch();
			JsonArray hits = searchResult.getJsonObject().get( "hits" ).getAsJsonObject().get( "hits" ).getAsJsonArray();
			results = new ArrayList<>( searchResult.getTotal() );

			for (JsonElement hit : hits) {
				results.add( searcher.convertQueryHit( hit.getAsJsonObject() ) );
			}
		}
	}
}
