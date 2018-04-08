package net.consensys.tools.ipfs.ipfsstore.dao.impl;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort.Order;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;


import net.consensys.tools.ipfs.ipfsstore.dao.IndexDao;
import net.consensys.tools.ipfs.ipfsstore.dto.IndexField;
import net.consensys.tools.ipfs.ipfsstore.dto.Metadata;
import net.consensys.tools.ipfs.ipfsstore.dto.query.Query;
import net.consensys.tools.ipfs.ipfsstore.exception.DaoException;
import net.consensys.tools.ipfs.ipfsstore.exception.NotFoundException;
import net.consensys.tools.ipfs.ipfsstore.utils.Strings;

import static java.util.Arrays.asList;

/**
 * ElasticSearch implementation of IndexDao
 *
 * @author Gregoire Jeanmart <gregoire.jeanmart@consensys.net>
 */
@Service
public class ElasticSearchIndexDao implements IndexDao {

    private static final Logger LOGGER = Logger.getLogger(ElasticSearchIndexDao.class);
    private static final String NULL = "null"; //must be lower case

    private static final String ERROR_NOT_NULL_OR_EMPTY = "cannot be null or empty";

    private final ObjectMapper mapper;

    private final TransportClient client;

    @Value("${parameters.indexNullValue}")
    private boolean indexNullValue;

    /*
     * Constructor
     */
    @Autowired
    public ElasticSearchIndexDao(TransportClient client) {
        mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));

        this.client = client;
    }


    @Override
    public String index(String indexName, String documentId, String hash, String contentType, List<IndexField> indexFields) throws DaoException {
        LOGGER.debug("Index document in ElasticSearch " + printSearchIndex(indexName, documentId, indexFields));

        // Validation
        if (Strings.isEmpty(indexName)) throw new IllegalArgumentException("indexName " + ERROR_NOT_NULL_OR_EMPTY);
        if (Strings.isEmpty(hash)) throw new IllegalArgumentException("hash " + ERROR_NOT_NULL_OR_EMPTY);

        try {
            DocWriteResponse response;
            Map<String, Object> source = new HashMap<>();

            // Populate the ElasticSearch Document
            source.put(IndexDao.HASH_INDEX_KEY, hash);
            source.put(IndexDao.CONTENT_TYPE_INDEX_KEY, contentType);
            if (indexFields != null) {
                source.putAll(convert(indexFields));
            }

            LOGGER.debug(source);

            if (!this.doesExist(indexName, documentId)) {
                response = client.prepareIndex(indexName.toLowerCase(), indexName.toLowerCase(), documentId)
                        .setSource(convertObjectToJsonString(source), XContentType.JSON)
                        .get();

            } else {
                response = client.prepareUpdate(indexName.toLowerCase(), indexName.toLowerCase(), documentId)
                        .setDoc(convertObjectToJsonString(source), XContentType.JSON)
                        .get();
            }

            LOGGER.debug("Document indexed ElasticSearch " + printSearchIndex(indexName, documentId, indexFields) + ". Result ID=" + response.getId());

            this.refreshIndex(indexName);

            return response.getId();

        } catch (Exception ex) {
            LOGGER.error("Error while indexing document into ElasticSearch " + printSearchIndex(indexName, documentId, indexFields), ex);
            throw new DaoException("Error while indexing document into ElasticSearch: " + ex.getMessage());
        }
    }


    @Override
    public Metadata searchById(String indexName, String id) throws DaoException, NotFoundException {
        LOGGER.debug("Search in ElasticSearch by ID " + printSearchDocument(indexName, id));

        // Validation
        if (Strings.isEmpty(indexName)) throw new IllegalArgumentException("indexName" + ERROR_NOT_NULL_OR_EMPTY);
        if (Strings.isEmpty(id)) throw new IllegalArgumentException("id" + ERROR_NOT_NULL_OR_EMPTY);

        try {
            GetResponse response = client.prepareGet(indexName.toLowerCase(), indexName.toLowerCase(), id).get();

            LOGGER.trace("Search one document in ElasticSearch " + printSearchDocument(indexName, id) + " : response=" + response);

            if (!response.isExists()) {
                throw new NotFoundException("Document " + printSearchDocument(indexName, id) + " not found");
            }

            Metadata metadata = convert(response.getIndex(), response.getId(), response.getSourceAsMap());

            LOGGER.debug("Search one document in ElasticSearch " + printSearchDocument(indexName, id) + " : " + metadata);

            return metadata;

        } catch (NotFoundException ex) {
            LOGGER.warn("Error while searching into ElasticSearch " + printSearchDocument(indexName, id), ex);
            throw ex;
        } catch (Exception ex) {
            LOGGER.error("Error while searching into ElasticSearch " + printSearchDocument(indexName, id), ex);
            throw new DaoException("Error while searching into ElasticSearch: " + ex.getMessage());
        }
    }


    @Override
    public List<Metadata> search(Pageable pageable, String indexName, Query query) throws DaoException {
        LOGGER.debug("Search documents in ElasticSearch " + printSearchQuery(indexName, query));

        // Validation
        if (pageable == null) throw new IllegalArgumentException("pageable " + ERROR_NOT_NULL_OR_EMPTY);
        if (Strings.isEmpty(indexName)) throw new IllegalArgumentException("indexName " + ERROR_NOT_NULL_OR_EMPTY);

        try {

            SearchRequestBuilder requestBuilder = client.prepareSearch(indexName)
                    .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                    .setQuery(convertQuery(query))
                    .setFrom(pageable.getOffset())
                    .setSize(pageable.getPageSize());

            if (pageable.getSort() != null) {
                for (Order order : pageable.getSort()) {
                    requestBuilder.addSort(new FieldSortBuilder(order.getProperty()).order(order.isAscending() ? SortOrder.ASC : SortOrder.DESC).unmappedType("date"));
                }
            }
            
            LOGGER.trace(requestBuilder);

            SearchResponse searchResponse = requestBuilder.execute().actionGet();

            LOGGER.trace("Search documents in ElasticSearch " + printSearchQuery(indexName, query) + " : " + searchResponse);

            List<Metadata> result = Arrays.stream(searchResponse.getHits().getHits())
                    .map(hit -> convert(hit.getIndex(), hit.getId(), hit.getSourceAsMap()))
                    .collect(Collectors.toList());

            LOGGER.debug("Search documents in ElasticSearch " + printSearchQuery(indexName, query) + " : " + result);

            return result;

        } catch (Exception ex) {
            LOGGER.error("Error while searching documents into ElasticSearch " + printSearchQuery(indexName, query), ex);
            throw new DaoException("Error while searching documents into ElasticSearch: " + ex.getMessage());
        }
    }

    @Override
    public long count(String indexName, Query query) throws DaoException {
        LOGGER.debug("Count in ElasticSearch " + printSearchQuery(indexName, query));

        // Validation
        if (Strings.isEmpty(indexName)) throw new IllegalArgumentException("indexName " + ERROR_NOT_NULL_OR_EMPTY);

        try {
          
          SearchResponse countResponse = client.prepareSearch(indexName)
                .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(convertQuery(query))
                .setSize(0)
                .get();

            LOGGER.trace("Count in ElasticSearch " + printSearchQuery(indexName, query) + " : " + countResponse);

            return countResponse.getHits().getTotalHits();

        } catch (Exception ex) {
            LOGGER.error("Error while counting into ElasticSearch " + printSearchQuery(indexName, query), ex);
            throw new DaoException("Error while counting into ElasticSearch: " + ex.getMessage());
        }
    }

    @Override
    public void createIndex(String indexName) throws DaoException {
        LOGGER.debug("Create index in ElasticSearch " + printSearchIndexName(indexName));

        // Validation
        if (Strings.isEmpty(indexName)) throw new IllegalArgumentException("indexName " + ERROR_NOT_NULL_OR_EMPTY);

        try {
            boolean exists = client.admin().indices()
                    .prepareExists(indexName)
                    .execute().actionGet().isExists();

            if (!exists) {
                client.admin().indices().prepareCreate(indexName).get();
                LOGGER.debug("Index created in ElasticSearch " + printSearchIndexName(indexName));

            } else {
                LOGGER.debug("Index already exists in ElasticSearch " + printSearchIndexName(indexName));
            }

        } catch (Exception ex) {
            LOGGER.error("Error while creating the index into ElasticSearch " + printSearchIndexName(indexName), ex);
            throw new DaoException("Error while creating the index into ElasticSearch: " + ex.getMessage());
        }
    }

    /**
     * Check if a document exists in E.S.
     *
     * @param index Index Name
     * @param id    Document ID
     * @return true/false
     * @throws ElasticsearchException
     */
    private Boolean doesExist(String index, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }

        GetResponse response = client.prepareGet(index, index, id).setRefresh(true).get();
        return response.isExists();
    }

    /**
     * Convert a list of IndexField (key/value) to a Map
     *
     * @param indexFields List of IndexField
     * @return Map
     */
    private Map<String, Object> convert(List<IndexField> indexFields) {
        if (indexFields == null) {
            return null;
        }

        return indexFields
                .stream()
                .collect(Collectors.toMap(
                        field -> field.getName(),
                        field -> handleNullValue(field.getValue())
                ));
    }

    /**
     * Replace null or empty string value by NULL to add it in the index (E.S. doesn't index null value)
     *
     * @param value Value
     * @return Value replaced by NULL if null or empty
     */
    private Object handleNullValue(Object value) {
        if (indexNullValue && (value == null || (value instanceof String && ((String) value).length() == 0))) {
            return NULL;
        } else {
            return value;
        }
    }

    /**
     * Convert a ElasticSearch result to a Metadata
     *
     * @param index     Index
     * @param id        ID
     * @param sourceMap Map of attributes
     * @return Metadata
     */
    private static Metadata convert(String index, String id, Map<String, Object> sourceMap) {
        String hash = null;
        String contentType = null;

        if (sourceMap != null) {

            // Extract special key __hash
            if (sourceMap.containsKey(HASH_INDEX_KEY) && sourceMap.get(HASH_INDEX_KEY) != null) {
                hash = sourceMap.get(HASH_INDEX_KEY).toString();
            }
            // Extract special key __content_type
            if (sourceMap.containsKey(CONTENT_TYPE_INDEX_KEY) && sourceMap.get(CONTENT_TYPE_INDEX_KEY) != null) {
                contentType = sourceMap.get(CONTENT_TYPE_INDEX_KEY).toString();
            }
        }

        return new Metadata(
                index,
                id,
                hash,
                contentType,
                convert(sourceMap));
    }

    /**
     * Convert a Map to a list of IndexField (key/value)
     *
     * @param indexFields Map
     * @return List of IndexField
     */
    private static List<IndexField> convert(Map<String, Object> indexFields) {
        if (indexFields == null) {
            return Collections.emptyList();
        }

        return indexFields.entrySet().stream()
                .map(field -> new IndexField(field.getKey(), field.getValue()))
                .collect(Collectors.toList()
                );
    }

    /**
     * Convert a IPFS-Store Query to a ElasticSearch query
     *
     * @param query IPFS-Store Query
     * @return ElasticSearch query
     */
    private QueryBuilder convertQuery(Query query) {
        LOGGER.trace("Converting query: " + query);

        BoolQueryBuilder elasticSearchQuery = QueryBuilders.boolQuery();

        if (query == null || query.getFilterClauses().isEmpty()) {
            return QueryBuilders.matchAllQuery();
        }

        query.getFilterClauses().forEach(f -> {

            Object value = handleNullValue(f.getValue());

            try {

                switch (f.getOperation()) {
                    case full_text:
                        elasticSearchQuery.must(QueryBuilders.multiMatchQuery(value, f.getNames()).lenient(true));
                        break;
                    case equals:
                        elasticSearchQuery.must(QueryBuilders.termQuery(f.getName(), value));
                        break;
                    case not_equals:
                        elasticSearchQuery.mustNot(QueryBuilders.termQuery(f.getName(), value));
                        break;
                    case contains:
                        elasticSearchQuery.must(QueryBuilders.matchQuery(f.getName(), value));
                        break;
                    case in:
                        elasticSearchQuery.filter(QueryBuilders.termsQuery(
                                f.getName(),
                                asList((Object[]) value).stream().map(o -> o.toString().toLowerCase()).collect(Collectors.toList())));
                        break;
                    case lt:
                        elasticSearchQuery.must(QueryBuilders.rangeQuery(f.getName()).lt(value));
                        break;
                    case lte:
                        elasticSearchQuery.must(QueryBuilders.rangeQuery(f.getName()).lte(value));
                        break;
                    case gt:
                        elasticSearchQuery.must(QueryBuilders.rangeQuery(f.getName()).gt(value));
                        break;
                    case gte:
                        elasticSearchQuery.must(QueryBuilders.rangeQuery(f.getName()).gte(value));
                        break;
                    default:
                        LOGGER.warn("Operation [" + f.getOperation() + "] not supported for  filter [" + f + "]- Ignore it!");
                        break;
                }

            } catch (Exception e) {
                LOGGER.warn("Error while converting filter [" + f + "] - Ignore it!", e);
            }
        });

        LOGGER.debug(elasticSearchQuery.toString());

        return elasticSearchQuery;
    }

    /**
     * Convert an object to a JSON String
     *
     * @param object Object to convert to a JSON
     * @return JSON representation of the object
     */
    private String convertObjectToJsonString(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException ex) {
            LOGGER.error("Exception occur:{}", ex);
        }
        return null;
    }

    /**
     * Refresh an index
     *
     * @param index Index name
     */
    private void refreshIndex(String index) {
        this.client.admin().indices().prepareRefresh(index).get();
    }

    private String printSearchIndexName(String indexName) {
        return "[indexName=" + indexName + "]";
    }

    private String printSearchIndex(String indexName, String documentId, List<IndexField> indexFields) {
        return "[indexName=" + indexName + ", documentId=" + documentId + ", indexFields=" + indexFields + "]";
    }

    private String printSearchDocument(String indexName, String id) {
        return "[indexName=" + indexName + ", id=" + id + "]";
    }

    private String printSearchQuery(String indexName, Query query) {
        return "[indexName=" + indexName + ", query=" + query + "]";
    }

}
