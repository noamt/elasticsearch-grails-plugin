package grails.plugins.elasticsearch.mapping

import grails.core.GrailsApplication
import grails.plugins.elasticsearch.ElasticSearchAdminService
import grails.plugins.elasticsearch.ElasticSearchContextHolder
import grails.plugins.elasticsearch.exception.MappingException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static grails.plugins.elasticsearch.mapping.MappingMigrationStrategy.*

/**
 * Created by @marcos-carceles on 26/01/15.
 */
class MappingMigrationManager {

    private ElasticSearchContextHolder elasticSearchContextHolder
    private ElasticSearchAdminService es
    private GrailsApplication grailsApplication
    private ConfigObject config

    private static final Logger LOG = LoggerFactory.getLogger(this)

    private Map getEsConfig() {
        grailsApplication.config.elasticSearch as Map
    }

    def applyMigrations(MappingMigrationStrategy migrationStrategy, Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {
        switch (migrationStrategy) {
            case delete:
                LOG.error("Delete a Mapping is no longer supported since Elasticsearch 2.0 (see https://www.elastic.co/guide/en/elasticsearch/reference/2.0/indices-delete-mapping.html)." +
                        " To prevent data loss, this strategy has been replaced by 'deleteIndex'")
                throw new MappingException()
            case deleteIndex:
                applyDeleteIndexStrategy(elasticMappings, mappingConflicts, indexSettings)
            case alias:
                applyAliasStrategy(elasticMappings, mappingConflicts, indexSettings)
                break;
            case none:
                LOG.error("Could not install mappings : ${mappingConflicts}. No migration strategy selected.")
                throw new MappingException()
        }
    }

    def applyDeleteIndexStrategy(Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {
        List deletedIndices = []
        mappingConflicts.each {
            SearchableClassMapping scm = it.scm
            if(!deletedIndices.contains(scm.indexName)) {
                deletedIndices << scm.indexName
                es.deleteIndex scm.indexName
                int nextVersion = es.getNextVersion(scm.indexName)
                es.createIndex scm.indexName, nextVersion, indexSettings
                es.waitForIndex scm.indexName, nextVersion //Ensure it exists so later on mappings are created on the right version
                es.pointAliasTo scm.indexName, scm.indexName, nextVersion
                es.pointAliasTo scm.indexingIndex, scm.indexName, nextVersion
                if(!esConfig.bulkIndexOnStartup) { //Otherwise, it will be done post content creation
                    if (!esConfig.migration.disableAliasChange) {
                        es.pointAliasTo scm.queryingIndex, scm.indexName, nextVersion
                    }
                }
            }
        }
        rebuildMappings(elasticMappings, deletedIndices)
    }

    def applyAliasStrategy(Map<SearchableClassMapping, Map> elasticMappings, List<MappingConflict> mappingConflicts, Map indexSettings) {
        def migratedIndices = buildNextIndexVersion(mappingConflicts, indexSettings)
        rebuildMappings(elasticMappings, migratedIndices)
    }

    private List<String> buildNextIndexVersion(List<MappingConflict> conflictingMappings, Map indexSettings) {
        def migratedIndices = []
        conflictingMappings.each {
            SearchableClassMapping scm = it.scm
            if (!migratedIndices.contains(scm.indexName)) {
                migratedIndices << scm.indexName
                println "index: $scm.indexName"
                LOG.debug("Creating new version and alias for conflicting mapping ${scm.indexName}/${scm.elasticTypeName}")
                boolean conflictOnAlias = es.aliasExists(scm.indexName)
                println "Conflict: $conflictOnAlias"
                println "Config: $esConfig.migration.aliasReplacesIndex"
                if (conflictOnAlias || esConfig.migration.aliasReplacesIndex) {
                    int nextVersion = es.getNextVersion(scm.indexName)
                    if (!conflictOnAlias) {
                        es.deleteIndex(scm.indexName)
                    }
                    es.createIndex scm.indexName, nextVersion, indexSettings
                    es.waitForIndex scm.indexName, nextVersion
                    //Ensure it exists so later on mappings are created on the right version
                    es.pointAliasTo scm.indexName, scm.indexName, nextVersion
                    es.pointAliasTo scm.indexingIndex, scm.indexName, nextVersion

                    if (!esConfig.bulkIndexOnStartup) { //Otherwise, it will be done post content creation
                        if (!conflictOnAlias || !esConfig.migration.disableAliasChange) {
                            es.pointAliasTo scm.queryingIndex, scm.indexName, nextVersion
                        }
                    }
                } else {
                    throw new MappingException("Could not create alias ${scm.indexName} to solve error installing mapping ${scm.elasticTypeName}, index with the same name already exists.", it.exception)
                }
            }
        }
        migratedIndices
    }

    private void rebuildMappings(Map<SearchableClassMapping, Map> elasticMappings, List migratedIndices) {
        //Recreate the mappings for all the indexes that were changed
        elasticMappings.each { SearchableClassMapping scm, elasticMapping ->
            if (migratedIndices.contains(scm.indexName)) {
                elasticSearchContextHolder.deletedOnMigration << scm.domainClass.clazz
                //Mark it for potential content index on Bootstrap
                if (scm.isRoot()) {
                    int newVersion = es.getLatestVersion(scm.indexName)
                    String indexName = es.versionIndex(scm.indexName, newVersion)
                    es.createMapping(indexName, scm.elasticTypeName, elasticMapping)
                }
            }
        }
    }

    void setElasticSearchContextHolder(ElasticSearchContextHolder elasticSearchContextHolder) {
        this.elasticSearchContextHolder = elasticSearchContextHolder
    }

    void setGrailsApplication(GrailsApplication grailsApplication) {
        this.grailsApplication = grailsApplication
    }

    void setConfig(ConfigObject config) {
        this.config = config
    }

    void setEs(ElasticSearchAdminService es) {
        this.es = es
    }

}
