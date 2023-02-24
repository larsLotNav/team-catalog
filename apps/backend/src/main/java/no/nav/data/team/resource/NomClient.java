package no.nav.data.team.resource;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import no.nav.data.common.exceptions.TechnicalException;
import no.nav.data.common.rest.RestResponsePage;
import no.nav.data.common.storage.StorageService;
import no.nav.data.common.storage.domain.GenericStorage;
import no.nav.data.common.utils.MetricUtils;
import no.nav.data.team.resource.domain.Resource;
import no.nav.data.team.resource.domain.ResourceEvent;
import no.nav.data.team.resource.domain.ResourceEvent.EventType;
import no.nav.data.team.resource.domain.ResourceRepository;
import no.nav.data.team.resource.domain.ResourceType;
import no.nav.data.team.resource.dto.NomRessurs;
import no.nav.data.team.settings.SettingsService;
import no.nav.data.team.settings.dto.Settings;
import org.apache.commons.codec.language.DoubleMetaphone;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static no.nav.data.common.utils.StreamUtils.convert;
import static no.nav.data.team.resource.ResourceState.*;
import static org.apache.lucene.queryparser.classic.QueryParserBase.escape;

@Slf4j
@Service
public class NomClient {

    private static final int MAX_SEARCH_RESULTS = 100;

    private static final Gauge gauge = MetricUtils.gauge()
            .name("nom_resources_gauge").help("Resources from nom indexed").register();
    private static final Gauge dbGauge = MetricUtils.gauge()
            .name("nom_resources_db_gauge").help("Resources from nom in db").register();
    private static final Counter counter = MetricUtils.counter()
            .name("nom_resources_read_counter").help("Resource events processed").register();
    private static final Counter discardCounter = MetricUtils.counter()
            .name("nom_resources_discard_counter").help("Resource events discarded").register();

    private final StorageService storage;
    private final SettingsService settingsService;
    private final ResourceRepository resourceRepository;

    private static NomClient instance;

    public static NomClient getInstance() {
        return instance;
    }

    public NomClient(StorageService storage, SettingsService settingsService, ResourceRepository resourceRepository) {
        this.storage = storage;
        this.settingsService = settingsService;
        this.resourceRepository = resourceRepository;
        // Initialize index
        try (var writer = createWriter()) {
            writer.commit();
        } catch (Exception e) {
            throw new TechnicalException("io error", e);
        }
        instance = this;
    }

    public Optional<Resource> getByNavIdent(String navIdent) {
        return ResourceState.get(navIdent)
                .or(() -> resourceRepository.findByIdent(navIdent).map(GenericStorage::toResource).map(Resource::stale))
                .filter(r -> shouldReturn(r.getNavIdent()));
    }

    public Optional<Resource> getByEmail(String email) {
        return ResourceState.getByEmail(email)
                .filter(r -> shouldReturn(r.getNavIdent()));
    }

    public Optional<String> getNameForIdent(String navIdent) {
        return Optional.ofNullable(navIdent)
                .filter(this::shouldReturn)
                .flatMap(this::getByNavIdent)
                .map(Resource::getFullName);
    }

    @SneakyThrows
    public RestResponsePage<Resource> search(String searchString) {

        try (var reader = createReader()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            var q = searchStringToCustomQuery(searchString, searcher);

            var top = searcher.search(q, MAX_SEARCH_RESULTS, Sort.RELEVANCE);
            log.debug("query '{}' hits {} returned {}", q.toString(), top.totalHits.value, top.scoreDocs.length);
            List<Resource> list = Stream.of(top.scoreDocs)
                    .map(sd -> getIdent(sd, searcher))
                    .filter(this::shouldReturn)
                    .map(navIdent -> getByNavIdent(navIdent).orElseThrow())

                    // this is easier than adding "membership" fields to the lucene index that needs to be kept in sync
                    .sorted(compareNavIdByMembershipStatus())
                    .collect(Collectors.toList());


            return new RestResponsePage<>(list, top.totalHits.value);
        } catch (IOException e) {
            log.error("Failed to read lucene index", e);
            throw new TechnicalException("Failed to read lucene index", e);
        }
    }

    @NotNull

    // navIds associated with memberships should be valued higher
    private Comparator<Resource> compareNavIdByMembershipStatus() {
//        return (a, b) -> {
//            var aIsMemberSomewhere = isMemberSomewhere(a.getNavIdent());
//            var bIsMemberSomewhere = isMemberSomewhere(b.getNavIdent());
//            if (aIsMemberSomewhere == bIsMemberSomewhere) return 0;
//            return (aIsMemberSomewhere) ? -1 : 1;
//        };
        return (a,b) -> 0;
    }

    private Boolean isMemberSomewhere(String navIdent) {
        // TODO, consult team and area memberships
        var rand = new Random(navIdent.hashCode());
        return rand.nextBoolean();
    }

    @SneakyThrows
    private Query searchStringToCustomQuery(String searchString, IndexSearcher searcher) {

        var phrasePhoneticQryBuilder = new MultiPhraseQuery.Builder().setSlop(4);
        var phraseNgramQryBuilder = new MultiPhraseQuery.Builder().setSlop(4);
        var phraseVerbatimQryBuilder = new MultiPhraseQuery.Builder().setSlop(4);

        var booleanPhoneticQryBuilder = new BooleanQuery.Builder();
        var booleanNgramQryBuilder = new BooleanQuery.Builder();
        var booleanVerbatimQryBuilder = new BooleanQuery.Builder();


        var esc = escape(searchString.toLowerCase().replace("-", " ")).trim();
        var splitString = esc.split(" +");
        var doubleMetaphoneEncoder = new DoubleMetaphone();

        for (var s : splitString) {
            var sMetaphone = doubleMetaphoneEncoder.doubleMetaphone(s);

            phrasePhoneticQryBuilder.add(new Term(FIELD_NAME_PHONETIC, sMetaphone));
            phraseNgramQryBuilder.add(new Term(FIELD_NAME_NGRAMS, s));
            phraseVerbatimQryBuilder.add(new Term(FIELD_NAME_VERBATIM, s));

            booleanPhoneticQryBuilder.add(new TermQuery(new Term(FIELD_NAME_PHONETIC, sMetaphone)), BooleanClause.Occur.SHOULD);
            booleanNgramQryBuilder.add(new TermQuery(new Term(FIELD_NAME_NGRAMS, s)), BooleanClause.Occur.SHOULD);
            booleanVerbatimQryBuilder.add(new TermQuery(new Term(FIELD_NAME_VERBATIM, s)), BooleanClause.Occur.SHOULD);
        }

        var phrasePhoneticQry = phrasePhoneticQryBuilder.build();
        var phraseNgramQry = phraseNgramQryBuilder.build();
        var phraseVerbatimQry = phraseVerbatimQryBuilder.build();

        var booleanPhoneticQry = booleanPhoneticQryBuilder.build();
        var booleanNgramQry = booleanNgramQryBuilder.build();
        var booleanVerbatimQry = booleanVerbatimQryBuilder.build();

//        var boost1 = 1.5f;
//        phrasePhoneticQry.createWeight(searcher, ScoreMode.COMPLETE, boost1);
//        phraseNgramQry.createWeight(searcher, ScoreMode.COMPLETE, boost1);
//        phraseVerbatimQry.createWeight(searcher, ScoreMode.COMPLETE, boost1);
//
//        var boost2 = 1f;
//        booleanPhoneticQry.createWeight(searcher, ScoreMode.COMPLETE, boost2);
//        booleanNgramQry.createWeight(searcher, ScoreMode.COMPLETE, boost2);
//        booleanVerbatimQry.createWeight(searcher, ScoreMode.COMPLETE, boost2);


        var overallBooleanQueryBuilder = new BooleanQuery.Builder()
                .add(booleanPhoneticQry, BooleanClause.Occur.SHOULD)
                .add(booleanNgramQry, BooleanClause.Occur.SHOULD)
                .add(booleanVerbatimQry, BooleanClause.Occur.SHOULD);

        var overallQueryBuilder = new BooleanQuery.Builder()
                .add(phrasePhoneticQry, BooleanClause.Occur.SHOULD)
                .add(phraseNgramQry, BooleanClause.Occur.SHOULD)
                .add(phraseVerbatimQry, BooleanClause.Occur.SHOULD)
                .add(new BoostQuery(overallBooleanQueryBuilder.build(), 0.05f), BooleanClause.Occur.SHOULD);


        var overallQry = overallQueryBuilder.build();
//        overallQry.createWeight(searcher,ScoreMode.TOP_DOCS,0.5f);

        return overallQueryBuilder.build();
    }


    public List<Resource> add(List<NomRessurs> nomResources) {
        if (count() == 0) { // State er tom == Startup => re-laste ResourceState fra basen
            storage.getAll(Resource.class).forEach( r ->
                    put(r)
            );
        }
        try {
            var toSave = new ArrayList<Resource>();
            try (var writer = createWriter()) {
                Map<String, Resource> existingState = findAll(convert(nomResources, NomRessurs::getNavident)).stream().collect(Collectors.toMap(r -> r.getNavIdent(), r -> r));
                for (NomRessurs nomResource : nomResources) {
                    var resource = new Resource(nomResource);
                    ResourceStatus status = shouldSave(existingState, resource);
                    if (status.shouldSave) {
                        toSave.add(resource);
                        if (status.previous != null) {
                            checkEvents(status.previous, resource);
                        }
                        put(resource);
                    }

                    var luceneIdent = resource.getNavIdent().toLowerCase();
                    var identTerm = new Term(FIELD_IDENT, luceneIdent);
                    if (resource.getResourceType() == ResourceType.OTHER) {
                        // Other resource types shouldn't be searchable, they should not ordinarily be a part of teams
                        writer.deleteDocuments(identTerm);
                        discardCounter.inc();
                        continue;
                    }
                    Document doc = new Document();
                    String name = resource.getGivenName() + " " + resource.getFamilyName();
                    doc.add(new TextField(FIELD_NAME_VERBATIM, name, Store.NO));
                    doc.add(new TextField(FIELD_NAME_NGRAMS, name, Store.NO));
                    doc.add(new TextField(FIELD_NAME_PHONETIC, name, Store.NO));

                    doc.add(new TextField(FIELD_IDENT, luceneIdent, Store.YES));

                    writer.updateDocument(identTerm, doc);
                    counter.inc();
                }
                storage.saveAll(toSave);
            }
            gauge.set(count());
            return toSave;
        } catch (IOException e) {
            log.error("Failed to write to index", e);
            throw new TechnicalException("Lucene error", e);
        }
    }

    private ResourceStatus shouldSave(Map<String, Resource> existing, Resource resource) {
        var newest = existing.get(resource.getNavIdent());
        boolean shouldSave = newest == null || newest.getOffset() < resource.getOffset();
        return new ResourceStatus(shouldSave, newest);
    }

    private ResourceStatus shouldSaveOld(Map<String, List<Resource>> existing, Resource resource) {
        var newest = existing.getOrDefault(resource.getNavIdent(), List.of()).stream().max(comparing(Resource::getOffset));
        boolean shouldSave = newest.isEmpty() || newest.get().getOffset() < resource.getOffset();
        return new ResourceStatus(shouldSave, newest.orElse(null));
    }

    private void checkEvents(Resource previous, Resource current) {
        if (!previous.isInactive() && current.isInactive()) {
            log.info("ident {} became inactive, creating ResourceEvent", current.getNavIdent());
            storage.save(ResourceEvent.builder().eventType(EventType.INACTIVE).ident(current.getNavIdent()).build());
        }
    }

    private Map<String, List<Resource>> findResources(List<String> idents) {
        return resourceRepository.findByIdents(idents).stream()
                .map(GenericStorage::toResource)
                .collect(groupingBy(Resource::getNavIdent));
    }

    public long count() {
        return ResourceState.count();
    }

    public long countDb() {
        return resourceRepository.count();
    }

    public void clear() {
        ResourceState.clear();
    }

    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1M")
    public void metrics() {
        gauge.set(count());
        dbGauge.set(countDb());
    }

    @Scheduled(initialDelayString = "PT10M", fixedRateString = "PT10M")
    public void cleanup() {
        resourceRepository.cleanup();
    }

    private String getIdent(ScoreDoc sd, IndexSearcher searcher) {
        try {
            return searcher.doc(sd.doc).get(FIELD_IDENT);
        } catch (Exception e) {
            throw new TechnicalException("io error", e);
        }
    }

    private boolean shouldReturn(String navIdent) {
        Settings settings = settingsService.getSettingsCached();
        // null only for tests
        return settings == null || !settings.isFilteredIdent(navIdent);
    }

    record ResourceStatus(boolean shouldSave, Resource previous) {

    }

}
