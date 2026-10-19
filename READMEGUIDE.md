# Guide: implementera maskinella och manuella regler i Rimfrost

Den här guiden förklarar **hur man bygger en API-baserad regel** — maskinell eller manuell — ovanpå
Rimfrosts regelramverk, och dokumenterar de **interna ramverkspaketen** på en detaljnivå som saknas
i deras egna README-filer. Guiden är skriven utifrån källkoden och dokumentationen på `main` i
ramverksrepona (exakta commits i [Källförteckning](#10-källförteckning)), med versionsnummer från
BOM:en för release `rimfrost-1_2`. Regeln i det här repot,
`rimfrost-regel-katt-sjukpenning-maskinell`, används som genomgående exempel.

> **Källhänvisningar:** Varje avsnitt inleds med en rad **Källor:** som anger var uppgifterna
> kommer ifrån, i formen `repo: sökväg`. Repo-förkortningarna och vilken katalog sökvägarna är
> relativa till står i [Källförteckning](#10-källförteckning). Där guiden drar en slutsats som inte
> står uttryckligen i en enskild fil (t.ex. att två dokument motsäger varandra) anges det.

> **Läs så här:** Kapitel 1–3 ger helheten och är gemensamma för alla regeltyper. Kapitel 4 är en
> steg-för-steg-guide för maskinella regler, kapitel 5 för manuella regler. Kapitel 6 är en
> referens över de interna paketen och kapitel 7 samlar fallgropar som inte syns i dokumentationen.

## Innehåll

1. [Rimfrost i korthet](#1-rimfrost-i-korthet)
2. [Paketkarta — vad varje beroende gör](#2-paketkarta--vad-varje-beroende-gör)
3. [Gemensamt för alla regler](#3-gemensamt-för-alla-regler)
4. [Maskinell regel steg för steg](#4-maskinell-regel-steg-för-steg)
5. [Manuell regel steg för steg](#5-manuell-regel-steg-för-steg)
6. [Referens: interna paket och klasser](#6-referens-interna-paket-och-klasser)
7. [Fallgropar och felsökning](#7-fallgropar-och-felsökning)
8. [Checklistor](#8-checklistor)
9. [Vidare läsning](#9-vidare-läsning)
10. [Källförteckning](#10-källförteckning)

---

## 1. Rimfrost i korthet

> **Källor:** core: `README.md`, `regler/README.md`, `regler/maskinell/README.md`,
> `regler/manuell/README.md`, `sequences/ratt_till_forsakring_maskinell.mmd`. Jämförelsetabellen är
> sammanställd från källorna i kapitel 4 och 5.

En **process** (Kogito/BPMN) orkestrerar ett handläggningsflöde och anropar **regler** — fristående
Quarkus-mikrotjänster — asynkront via Kafka. Varje regel läser ärendet (*handläggningen*) från
Handläggningstjänsten, bedömer något, skriver tillbaka sitt resultat och svarar processen med ett
**utfall**.

```
Process ──Kafka: RegelRequest (handlaggningId, aktivitetId, replyTo)──► Regel
                                                                         │ GET  /handlaggning/{id}
                                                                         │ ... regellogik, externa API:er ...
                                                                         │ PUT  /handlaggning/{id}
Process ◄──Kafka: RegelResponse (handlaggningId, utfall) på replyTo──────┘
```

### Välj regeltyp

| | Maskinell | Manuell |
|---|---|---|
| Vem bedömer | Koden, helt automatiskt | En handläggare i portalen |
| Svarstid | Direkt (sekunder, eller timmar vid återförsök) | Tills handläggaren klickar *klar* |
| Ramverk | `rimfrost-framework-regel-maskinell` | `rimfrost-framework-regel-manuell` |
| Du implementerar | `RegelMaskinellServiceInterface.processRegel()` | `readData()`, `updateData()`, `done()` + REST-controller |
| Databas | Nej | Ja (PostgreSQL, korrelationsdata) |
| OUL-uppgift | Nej | Ja, skapas och avslutas av ramverket |
| REST-API | Nej (bara hälsa) | Ja, mot regelns micro-frontend/BFF |
| Exempel | Det här repot, `rimfrost-regel-rtf-maskinell` | `rimfrost-regel-rtf-manuell` |

Tumregel: om bedömningen kan göras på data som finns i handläggningen eller i ett API, gör en
maskinell regel som svarar `JA` när allt är uppfyllt och `UTREDNING` annars — och låt processen
skicka `UTREDNING`-fallen vidare till en manuell regel.

### Utfall

> **Källor:** regel: `docs/krav.md` (krav FRALL-FR-03.2: `JA`, `NEJ`, `UTREDNING`, `ERROR`);
> mask: `docs/krav.md`; regel: `logic/RegelMapper.java` (felsvar sätter `Utfall.ERROR`). Enumen
> själv ligger i `rimfrost-framework-regel-asyncapi`, som inte granskats lokalt.

`se.fk.rimfrost.framework.regel.Utfall` (från `rimfrost-framework-regel-asyncapi`):

| Utfall | Betydelse |
|---|---|
| `JA` | Villkoren är uppfyllda |
| `NEJ` | Villkoren är inte uppfyllda (används när regeln själv kan avgöra avslag) |
| `UTREDNING` | Kan inte avgöras maskinellt — kräver manuell utredning |
| `ERROR` | Sätts av ramverket när ett felsvar skickas (se [3.6](#36-felkoder-och-felsvar)) |

---

## 2. Paketkarta — vad varje beroende gör

> **Källor:** core: `README.md` (arvsträd), `FRAMEWORK.md` (beroendegraf),
> `bom/rimfrost-1_2-bom/rimfrost.md` (koordinater och versioner); `pom.xml` i regel, mask, man och
> roul (direkta beroenden); katt: `pom.xml` (adapterversioner).

### Beroendeträd

```
Maskinell regel (t.ex. det här repot)
└── rimfrost-framework-regel-maskinell        processRegel-kontraktet, orkestrering, RetryUtil
    └── rimfrost-framework-regel              Kafka in/ut, config.yaml, basklass, RegelUtils
        ├── rimfrost-framework-regel-asyncapi       genererade Kafka-DTO:er, Utfall, RegelErrorInformation
        ├── rimfrost-framework-handlaggning-adapter HandlaggningAdapter + domänmodell (Handlaggning, Yrkande …)
        ├── rimfrost-framework-regel-error-codes    RegelFelkod (ramverkets felkoder)
        ├── rimfrost-framework-referensdata-interface  ErbjudandeReferensdataInterface
        └── rimfrost-framework-sid-adapter          SidAdapter (skyddad identitet)

Manuell regel
└── rimfrost-framework-regel-manuell          REST-controller, middleware, done-flöde, SID-kontroll
    └── rimfrost-framework-regel-oul          OulUppgiftService, korrelationslagring (JPA/Flyway)
        ├── rimfrost-framework-regel          (som ovan)
        ├── rimfrost-framework-oul            Kafka-konsument för OUL-statusnotifieringar
        └── rimfrost-framework-oul-adapter    REST-klient mot OUL
```

### Maven-koordinater (release `rimfrost-1_2`)

| Artefakt | groupId | Version |
|---|---|---|
| `rimfrost-framework-regel` (+ `tests`) | `se.fk.rimfrost.framework.regel` | 1.4.4 |
| `rimfrost-framework-regel-maskinell` (+ `tests`) | `se.fk.rimfrost.framework.regel.maskinell` | 1.1.8 |
| `rimfrost-framework-regel-manuell` (+ `tests`) | `se.fk.rimfrost.framework.regel.manuell` | 1.4.5 |
| `rimfrost-framework-regel-oul` (+ `tests`) | `se.fk.rimfrost.framework.regel.oul` | 0.1.2 |
| `rimfrost-framework-oul` | `se.fk.rimfrost.framework.oul` | 1.1.3 |
| `rimfrost-framework-handlaggning-adapter` | `se.fk.rimfrost.framework.handlaggning` | 1.2.4 |
| `rimfrost-framework-sid-adapter` | `se.fk.rimfrost.framework.sid` | 0.0.1 |
| `rimfrost-adapter-folkbokford` | `se.fk.rimfrost.adapter.folkbokford` | 1.1.5 (används här) |
| `rimfrost-adapter-arbetsgivare` | `se.fk.rimfrost.adapter.arbetsgivare` | 1.1.4 (används här) |

Den kompletta listan finns i `rimfrost/bom/rimfrost-1_2-bom/rimfrost.md`. Artefakterna ligger i
Försäkringskassans GitHub Packages-repo och kräver `GITHUB_ACTOR`/`GITHUB_TOKEN` (se
[`settings.xml`](settings.xml)).

> **`tests`-classifiern** innehåller ramverkens abstrakta testklasser och WireMock-hjälpare. Ta
> alltid med både ramverkets och `rimfrost-framework-regel`:s test-JAR i `test`-scope — se
> [`pom.xml`](pom.xml).

---

## 3. Gemensamt för alla regler

Allt i det här kapitlet kommer från `rimfrost-framework-regel` och gäller både maskinella och
manuella regler.

### 3.1 Kafka-kontraktet

> **Källor:** regel: `presentation/kafka/RegelConsumer.java`, `RegelMessageHandler.java`,
> `RegelKafkaMapper.java`, `integration/kafka/RegelKafkaProducer.java` (`Objects.requireNonNull(topic)`,
> `OutgoingKafkaRecordMetadata`), `logic/RegelRequestHandlerBase.java` (`createCloudEvent`: `type`
> = responses-topic, `source` = `kafka.source`), `src/main/resources/application.properties`;
> core: `ASYNC_ROUTING.md`.

Ramverket äger två logiska kanaler. Du konfigurerar bara de fysiska topic-namnen:

| Kanal | Riktning | Klass | Du sätter |
|---|---|---|---|
| `regel-requests` | In | `RegelConsumer` → `RegelMessageHandler` → `RegelRequestHandlerInterface` | `mp.messaging.incoming.regel-requests.topic` |
| `regel-responses` | Ut | `RegelKafkaProducer` | `mp.messaging.outgoing.regel-responses.topic` |

Connector, (de)serializer och `auto.offset.reset` sätts redan i ramverkets egna
`application.properties` (som följer med i JAR:en), så de behöver inte upprepas.

**`replyTo` styr vart svaret skickas.** Det utgående topic-namnet du konfigurerar används *inte* som
destination — det används bara som `type` i svarets CloudEvent. Svaret skickas alltid till
`replyTo` från förfrågans payload (`RegelDataRequest.replyTo()`), via `OutgoingKafkaRecordMetadata`.
Saknas `replyTo` kastas `NullPointerException` och svaret går förlorat. Hårdkoda aldrig ett
svarstopic. Se `rimfrost/ASYNC_ROUTING.md` för hela bakgrunden.

**CloudEvent-fälten** (`kogitoprocinstanceid`, `kogitorootprociid` m.fl.) måste ekas tillbaka
oförändrade för att Kogito ska kunna korrelera svaret till rätt processinstans. Ramverket gör det
åt dig via `CloudEventData` — du behöver aldrig röra dem i en maskinell regel.

`MDC` sätts med `PROCESSID = handlaggningId` under hela hanteringen av ett meddelande, så alla
loggrader från din regel blir spårbara per ärende.

### 3.2 `config.yaml` — regelns metadata

> **Källor:** regel: `integration/config/RegelConfigProviderYaml.java` (laddningsordning),
> `integration/config/YamlConfigLoader.java` (schemavalidering),
> `src/main/resources/schema/regel_schema.yaml`; core: `regler/CONFIG_YAML.md`; man:
> `presentation/rest/RegelManuellController.java` (`getUtokadUppgiftsbeskrivning` läser sektionen
> utan null-kontroll), `logic/RegelManuellRequestHandler.java` (vilka fält som skickas till OUL);
> regel: `logic/RegelRequestHandlerBase.java` (`createUppgiftSpecifikation`).

Varje regel har `src/main/resources/config.yaml` med fyra obligatoriska sektioner (`uppgift`,
`specifikation`, `regel`, `lagrum`) och en valfri (`utokadUppgiftsbeskrivning`). Se
[`src/main/resources/config.yaml`](src/main/resources/config.yaml) för ett komplett exempel och
`rimfrost/regler/CONFIG_YAML.md` för fältens betydelse.

Hur filen laddas (`RegelConfigProviderYaml`, `@Startup`):

1. Miljövariabeln `REGEL_CONFIG_PATH` om den är satt (används i containern, se
   [`Dockerfile.jvm`](src/main/docker/Dockerfile.jvm)).
2. Annars property `application.config.path`.
3. Finns inte filen på angiven sökväg loggas en varning och `config.yaml` söks på classpath.

Filen **valideras mot JSON Schema** (`schema/regel_schema.yaml` i `rimfrost-framework-regel`)
innan den parsas. Schemat har `additionalProperties: false` på varje nivå — ett felstavat eller
okänt fält ger startfel (`Config violates JSON Schema: …`). Fritt innehåll läggs i ett
`metadata`-objekt under respektive sektion.

| Fält | Maskinell | Manuell |
|---|---|---|
| `uppgift.path` | Används inte | **Obligatoriskt i praktiken** — blir uppgiftens `url` i OUL, dvs. vart portalen navigerar |
| `utokadUppgiftsbeskrivning` | Används inte | **Krävs** — `GET /utokadUppgiftsbeskrivning` ger 500 om sektionen saknas |
| `specifikation.id`/`version` | Skrivs till `Uppgift.uppgiftSpecifikation` | Samma |
| `specifikation.namn`, `uppgiftbeskrivning`, `verksamhetslogik`, `roll` | Bara metadata | Skickas till OUL när uppgiften skapas |

Generera nya UUID:er för varje sektion i en ny regel — återanvänd aldrig från en annan regel.

### 3.3 Handläggningens domänmodell

> **Källor:** Modellens källkod (`rimfrost-framework-handlaggning-adapter`) har inte granskats
> lokalt; fälten är härledda från hur de används i katt: `logic/KattSjukpenningService.java`,
> `logic/KattUppgifter.java`, test: `KattSjukpenningTestData.java`,
> `src/test/resources/mappings/get-handlaggning-1234.json`, och i regel:
> `logic/RegelRequestHandlerBase.java` (`createHandlaggningUpdate`, `createUppgift`).

Modellen kommer från `rimfrost-framework-handlaggning-adapter`
(`se.fk.rimfrost.framework.handlaggning.model`) och är genererad med Immutables — bygg alltid nya
objekt med `Immutable<Typ>.builder()`, gärna `.from(befintligt)`.

```
Handlaggning
├── id, version, processInstansId, skapadTS, avslutadTS, handlaggningspecifikationId
└── yrkande: Yrkande
    ├── id, version, erbjudandeId, yrkandeDatum, yrkandeFrom, yrkandeTom, yrkandeStatus, avsikt
    ├── individYrkandeRoller: List<IndividYrkandeRoll>
    │   └── individ: Idtyp (typId, varde = t.ex. personnummer), yrkandeRollId
    └── produceradeResultat: List<ProduceratResultat>
        └── id, version, typ, resultatFrom, resultatTom, yrkandeStatus, avslagsanledning, data (JSON-sträng)

HandlaggningUpdate  — det du skriver tillbaka (PUT)
├── samma huvudfält som Handlaggning
├── underlag: List<Underlag>      (typ, version, data som JSON-sträng)
└── uppgift: Uppgift              (id, version, aktivitetId, skapadTs, utfordTs, planeradTs,
                                   utforarId, uppgiftStatus, fSSAinformation, uppgiftSpecifikation)
```

Användbara mönster, hämtade från det här repot:

- **Sökande individ:** `yrkande.individYrkandeRoller().getFirst().individ().varde()` ger
  personnumret ([`KattSjukpenningService.java`](src/main/java/se/fk/github/maskinellregelkattsjukpenning/logic/KattSjukpenningService.java)).
- **Data som ett tidigare steg producerat** ligger i `produceradeResultat`, identifierat med `typ`
  och med payload som JSON-sträng i `data`. Läs med `objectMapper.readTree(resultat.data())` och
  tolka alltid trasig JSON som "villkor ej uppfyllt", aldrig som krasch
  ([`KattUppgifter.java`](src/main/java/se/fk/github/maskinellregelkattsjukpenning/logic/KattUppgifter.java)).
- **Underlag** är regelns spårbara "bevis" — allt regeln baserat sitt beslut på bör skrivas som
  underlag med en stabil `typ` och en `version` du ökar när formatet ändras.

### 3.4 `RegelUtils` — hjälpmetoder

> **Källor:** regel: `logic/RegelUtils.java`; mask: `logic/RegelMaskinellRequestHandler.java`
> (`catch (Exception e)` fångar inte `Error`); katt: `logic/KattSjukpenningService.java`
> (`catch (InternalError e)`).

`se.fk.rimfrost.framework.regel.logic.RegelUtils` (statiska metoder):

| Metod | Gör |
|---|---|
| `createUnderlag(typ, version, objekt, objectMapper)` | Serialiserar `objekt` till JSON och bygger ett `Underlag`. Kastar `InternalError` om serialiseringen misslyckas |
| `createProduceratResultatData(objekt, objectMapper)` | Serialiserar till JSON-sträng för `ProduceratResultat.data`. Kastar `InternalError` |
| `mergeProduceradeResultat(nya, tidigare)` | Slår ihop listor: samma `id` → det nya vinner, övriga tidigare behålls |
| `createYrkandeWithUpdatedProduceradeResultat(yrkande, nya)` | Nytt `Yrkande` med sammanslagna resultat |

Notera att `InternalError` är ett `Error`, inte en `Exception` — det fångas *inte* av ramverkets
`catch (Exception e)`. Fånga det själv, som i `KattSjukpenningService`.

### 3.5 Versionshantering

> **Källor:** core: `regler/VERSIONING.md`; mask: `logic/RegelMaskinellRequestHandler.java`
> (`updateHandlaggning`, `HandlaggningUpdateConflictException`); man:
> `logic/RegelManuellMiddlewareService.java` (konflikt → 500), `logic/RegelManuellRequestHandler.java`
> (`handleUppgiftDone` stegar `Uppgift.version`); roul: `logic/OulUppgiftService.java`
> (`createOulUppgift` stegar handläggningens version, `handleOulStatus` gör det inte).

Ramverket stegar **aldrig** versionsfält åt dig (utom i några av sina egna skrivningar, se nedan).
Hela regelverket står i `rimfrost/regler/VERSIONING.md`; kortversionen:

| Fält | När ska regeln stega |
|---|---|
| `HandlaggningUpdate.version` | När regeln gör en meningsfull tillståndsändring: `handlaggning.version() + 1`. Annars oförändrat |
| `ProduceratResultat.version` | När regeln ändrar ett befintligt resultat: `ImmutableProduceratResultat.builder().from(r).version(r.version() + 1)…` |
| `Uppgift.version` | Hanteras av ramverket i manuella regler |

En versionskonflikt från Handläggningstjänsten (`HandlaggningException.ErrorType.CONFLICT`) tolkas
av ramverket som ett **programmeringsfel i regeln** och görs aldrig om.

### 3.6 Felkoder och felsvar

> **Källor:** regel: `logic/RegelMapper.java`, `logic/RegelRequestHandlerBase.java`
> (`createRegelErrorInformation`); mask: `logic/RegelMaskinellRequestHandler.java` och man:
> `logic/RegelManuellRequestHandler.java` (var respektive felkod används); katt:
> `logic/RegelFelkod.java`, `logic/KattSjukpenningService.java` (`createErrorResult`). Klassen
> `RegelFelkod` i `rimfrost-framework-regel-error-codes` har inte granskats lokalt — tabellen visar
> de koder som ramverken faktiskt använder.

Ett felsvar är ett vanligt `RegelResponse` med `utfall = ERROR` och en `RegelErrorInformation`
(`felkod`, `felmeddelande`). Ramverkets egna koder finns i
`se.fk.rimfrost.framework.regel.error.RegelFelkod`:

| Felkod | Orsak |
|---|---|
| `RIMFROST_HANDLAGGNING_READ_FAILURE` | GET handläggning misslyckades (efter återförsök i maskinell regel) |
| `RIMFROST_HANDLAGGNING_WRITE_FAILURE` | PUT handläggning misslyckades eller versionskonflikt |
| `RIMFROST_OTHER` | Oväntad exception i regeln eller ramverket |

Regelspecifika koder definierar du själv, prefixade med regelns namn, som i
[`RegelFelkod.java`](src/main/java/se/fk/github/maskinellregelkattsjukpenning/logic/RegelFelkod.java)
(`KATT_SJUKPENNING_MASKINELL_…`). Inkludera handläggnings-id, processinstans-id och aktivitets-id
i felmeddelandet — det är det enda som går att felsöka på i processen.

### 3.7 Quarkus-indexering av ramverksberoenden

> **Källor:** katt: `src/main/resources/application.properties`; `src/main/resources/application.properties`
> i regel, mask, man och roul (vilka beroenden ramverken själva indexerar). Symptomen är allmän
> Quarkus/ArC-erfarenhet, inte dokumenterade i repona.

Ramverkens CDI-bönor (consumer, producer, request handler, config provider …) hittas bara om
Quarkus indexerar JAR:erna. Varje regel måste därför deklarera `quarkus.index-dependency.*` för
ramverket och för alla adaptrar den injicerar:

```properties
quarkus.index-dependency.rimfrost-framework-regel-maskinell.group-id=se.fk.rimfrost.framework.regel.maskinell
quarkus.index-dependency.rimfrost-framework-regel-maskinell.artifact-id=rimfrost-framework-regel-maskinell
quarkus.index-dependency.rimfrost-adapter-folkbokford.group-id=se.fk.rimfrost.adapter.folkbokford
quarkus.index-dependency.rimfrost-adapter-folkbokford.artifact-id=rimfrost-adapter-folkbokford
```

Symptom på saknad indexering: `Unsatisfied dependency for type …Adapter` vid uppstart, eller att
regeln startar men aldrig konsumerar något från Kafka.

---

## 4. Maskinell regel steg för steg

### 4.1 Vad ramverket gör åt dig

> **Källor:** mask: `logic/RegelMaskinellRequestHandler.java` (hela flödet och felkoderna),
> `src/main/resources/application.properties` (default `retry.intervals`); regel:
> `presentation/kafka/RegelConsumer.java` (`@Blocking`), `logic/RegelRequestHandlerBase.java`
> (`createCloudEvent`, `createUppgift`); core: `FAULT_HANDLING_OVERVIEW.md` (avsnitt 1). Totaltiden
> (~17 h) är summan av default-intervallen.

`RegelMaskinellRequestHandler` orkestrerar hela flödet. Din kod körs bara i steg 4:

```
1. Kafka-meddelande på regel-requests → RegelDataRequest
2. Bygg CloudEventData (type = ditt responses-topic, source = kafka.source)
3. GET handläggning — med RetryUtil och retry.intervals
      misslyckas alla försök → felsvar RIMFROST_HANDLAGGNING_READ_FAILURE
4. Bygg Uppgift (nytt UUID, version 1, aktivitetId, uppgiftSpecifikation från config.yaml, status null)
   → regelService.processRegel(RegelMaskinellRequest)          ◄── DIN KOD
      exception          → felsvar RIMFROST_OTHER
      ErrorResult        → felsvar med din RegelErrorInformation (ingen PUT görs)
      SuccessResult      → fortsätt
5. PUT handlaggningUpdate — med RetryUtil (bara skrivningen görs om, inte din logik)
      CONFLICT           → felsvar RIMFROST_HANDLAGGNING_WRITE_FAILURE direkt
      alla försök slut   → felsvar RIMFROST_HANDLAGGNING_WRITE_FAILURE
6. RegelResponse(utfall) till replyTo
```

Allt körs synkront på Kafka-konsumentens tråd (`@Blocking`). Med produktionsintervallen kan ett
enskilt meddelande alltså blockera i flera timmar om ett API ligger nere — det är avsiktligt.

### 4.2 Projektstruktur

> **Källor:** katt: hela repot (filstrukturen); core: `regler/maskinell/README.md` (template-repot).

Utgå från `rimfrost-template-regel-maskinell` eller kopiera det här repot:

```
pom.xml                         parent fk-maven-quarkus-parent, ramverk + adaptrar + test-JAR:er
settings.xml                    GitHub Packages-autentisering
src/main/docker/Dockerfile.jvm  kopierar config.yaml och sätter REGEL_CONFIG_PATH
src/main/resources/
├── application.properties      topics, base-urls, indexering
└── config.yaml                 regelns metadata
src/main/java/.../logic/
├── <Regel>Service.java         implementerar RegelMaskinellServiceInterface
├── <Regel>Bedomning.java       ren bedömningslogik (record), utan beroenden
├── <Regel>Uppgifter.java       tolkning av handläggningsdata (statiska, testbara metoder)
└── RegelFelkod.java            regelspecifika felkoder
src/test/
├── java/...                    se 4.9
└── resources/
    ├── application.properties  in-memory Kafka, retry.intervals=1, config-test.yaml
    ├── config-test.yaml
    └── mappings/*.json         WireMock-stubbar
```

### 4.3 `pom.xml`

> **Källor:** katt: `pom.xml`, `settings.xml`; mask: `README.md` (avsnitt Test-JAR).

Minsta uppsättning — jämför med [`pom.xml`](pom.xml):

```xml
<dependency>  <!-- ramverket -->
  <groupId>se.fk.rimfrost.framework.regel.maskinell</groupId>
  <artifactId>rimfrost-framework-regel-maskinell</artifactId>
  <version>1.1.8</version>
</dependency>
<!-- en adapter per externt API regeln anropar -->
<dependency>
  <groupId>se.fk.rimfrost.adapter.folkbokford</groupId>
  <artifactId>rimfrost-adapter-folkbokford</artifactId>
  <version>1.1.5</version>
</dependency>

<!-- test -->
<dependency>
  <groupId>se.fk.rimfrost.framework.regel</groupId>
  <artifactId>rimfrost-framework-regel</artifactId>
  <version>1.4.4</version>
  <classifier>tests</classifier>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>se.fk.rimfrost.framework.regel.maskinell</groupId>
  <artifactId>rimfrost-framework-regel-maskinell</artifactId>
  <version>1.1.8</version>
  <classifier>tests</classifier>
  <scope>test</scope>
</dependency>
<!-- + smallrye-reactive-messaging-in-memory, quarkus-junit, quarkus-junit-mockito, wiremock, awaitility -->
```

Behåll `-parameters` i kompilatorkonfigurationen och `violations.maxViolations=0` (FK:s kodstandard
bryter bygget vid varningar).

### 4.4 `application.properties`

> **Källor:** katt: `src/main/resources/application.properties`; mask: `README.md` (avsnitt
> Konfiguration), `src/main/resources/application.properties`; regel:
> `src/main/resources/application.properties`.

```properties
# Indexering (se 3.7) — ramverket + varje adapter
quarkus.index-dependency.rimfrost-framework-regel-maskinell.group-id=se.fk.rimfrost.framework.regel.maskinell
quarkus.index-dependency.rimfrost-framework-regel-maskinell.artifact-id=rimfrost-framework-regel-maskinell

quarkus.container-image.name=rimfrost-regel-<namn>-maskinell

# Kafka
kafka.source=Regel<Namn>Maskinell                                     # CloudEvent source i svaret
mp.messaging.incoming.regel-requests.topic=<namn>-maskinell-requests
mp.messaging.outgoing.regel-responses.topic=<namn>-maskinell-responses # blir CloudEvent type

# Regelmetadata (åsidosätts av REGEL_CONFIG_PATH i containern)
application.config.path=src/main/resources/config.yaml

# REST — handläggning krävs alltid, resten per adapter
handlaggning.api.base-url=http://rimfrost-k8s-handlaggning:8080
folkbokford.api.base-url=http://rimfrost-k8s-folkbokford:8080
%dev.handlaggning.api.base-url=http://localhost:8080

# Valfritt: återförsöksintervall i sekunder. Ramverkets default:
# 15,30,60,120,240,480,960,1920,3840,7680,15360,30720  (13 försök, ~17 h totalt)
# rimfrost.framework.regel.maskinell.retry.intervals=...
```

### 4.5 `config.yaml`

> **Källor:** katt: `src/main/resources/config.yaml`; regel:
> `src/main/resources/schema/regel_schema.yaml` (`path` ej obligatoriskt).

Som i [3.2](#32-configyaml--regelns-metadata). För en maskinell regel kan `uppgift.path` och
`utokadUppgiftsbeskrivning` utelämnas.

### 4.6 Serviceklassen

> **Källor:** mask: `logic/RegelMaskinellServiceInterface.java`, `logic/dto/RegelMaskinellRequest.java`,
> `logic/dto/RegelMaskinellResult.java`, `logic/dto/RegelMaskinellSuccessResult.java`,
> `logic/dto/RegelMaskinellErrorResult.java`, `logic/RegelMaskinellMapper.java`,
> `logic/RegelMaskinellRequestHandler.java` (PUT:ar `handlaggningUpdate` oförändrad); katt:
> `logic/KattSjukpenningService.java`.

En maskinell regel är **en** CDI-böna:

```java
@ApplicationScoped
public class MinRegelService implements RegelMaskinellServiceInterface
{
   @Inject ObjectMapper objectMapper;
   @Inject FolkbokfordAdapter folkbokfordAdapter;

   @ConfigProperty(name = "rimfrost.framework.regel.maskinell.retry.intervals")
   List<Integer> retryIntervals;

   @Override
   public RegelMaskinellResult processRegel(RegelMaskinellRequest request)
   {
      var handlaggning = request.handlaggning();
      var yrkande = handlaggning.yrkande();

      // 1. Hämta extern data — med återförsök (4.7)
      // 2. Bedöm (4.8)
      // 3. Bygg underlag
      // 4. Bygg HandlaggningUpdate och returnera SuccessResult
   }
}
```

`RegelMaskinellRequest` innehåller:

| Fält | Innehåll |
|---|---|
| `handlaggning()` | Hela handläggningen som den lästes i steg 3 |
| `uppgift()` | Uppgiften ramverket byggt åt dig — utgå alltid från den |
| `processInstansId()` | Kogito-processinstansen; ska in i `HandlaggningUpdate.processInstansId` |

Resultatet är ett sealed interface med exakt två implementationer:

```java
// Lyckad bedömning — ramverket gör PUT och skickar utfallet
return ImmutableRegelMaskinellSuccessResult.builder()
      .utfall(Utfall.JA)
      .handlaggningUpdate(handlaggningUpdate)
      .build();

// Kan inte genomföras — ramverket skickar felsvar, ingen PUT
return ImmutableRegelMaskinellErrorResult.builder()
      .regelErrorInformation(info)
      .build();
```

**Bygga `HandlaggningUpdate`.** Ramverket skickar ditt objekt som det är — inget kopieras över från
den lästa handläggningen, så fält du utelämnar skickas inte med. Fyll i alla fält. Mönstret från
`KattSjukpenningService`:

```java
var uppgift = ImmutableUppgift.builder().from(request.uppgift())
      .utfordTs(OffsetDateTime.now())          // markera uppgiften som utförd
      .build();

var update = ImmutableHandlaggningUpdate.builder()
      .id(handlaggning.id())
      .version(handlaggning.version() + 1)     // regeln stegar (3.5)
      .yrkande(yrkande)                        // eller RegelUtils.createYrkandeWithUpdatedProduceradeResultat(...)
      .processInstansId(request.processInstansId())
      .skapadTS(handlaggning.skapadTS())
      .avslutadTS(handlaggning.avslutadTS())
      .handlaggningspecifikationId(handlaggning.handlaggningspecifikationId())
      .underlag(underlag)
      .uppgift(uppgift)
      .build();
```

Det finns också `RegelMaskinellMapper.toHandlaggningUpdate(handlaggning, underlag, yrkande, uppgift,
processInstansId)` som bygger samma objekt men **behåller** `handlaggning.version()` oförändrat.

### 4.7 Anropa externa API:er med `RetryUtil`

> **Källor:** mask: `logic/helpers/retry/RetryUtil.java`, `Result.java`,
> `RetriesExhaustedException.java`; katt: `logic/KattSjukpenningService.java`
> (`getFolkbokfordResponse`, `getArbetsgivareResponse`); core: `FAULT_HANDLING_OVERVIEW.md`
> (regelns ansvar för återförsök). Motsägelsen om exceptions: `FAULT_HANDLING_OVERVIEW.md` säger att
> adapterns exceptions fångas internt, men `RetryUtil.java` saknar try/catch runt `supplier.get()`.

Ramverket gör återförsök mot Handläggningstjänsten, **men inte mot dina adaptrar** — det är regelns
ansvar. Använd `RetryUtil` från ramverket:

```java
FolkbokfordResponse svar;
try
{
   svar = RetryUtil.getWithRetries(() -> hamtaFolkbokford(pnr), retryIntervals);
}
catch (RetriesExhaustedException e)
{
   return felsvar(RegelFelkod.MIN_REGEL_FOLKBOKFORING_READ_ERROR, "Failed to read folkbokford response", request);
}

private Result<FolkbokfordResponse> hamtaFolkbokford(String pnr)
{
   try
   {
      return Result.of(folkbokfordAdapter.getFolkbokfordInfo(
            ImmutableFolkbokfordRequest.builder().personnummer(pnr).build()));
   }
   catch (FolkbokfordException e)
   {
      // 404 är ett giltigt svar ("finns inte") — försök inte igen
      return e.getErrorType() == FolkbokfordException.ErrorType.NOT_FOUND ? Result.of(null) : Result.empty();
   }
}
```

Kontraktet:

| Supplier returnerar | `RetryUtil` gör |
|---|---|
| `Result.of(värde)` (även `null`) | Returnerar värdet direkt |
| `Result.empty()` | Väntar nästa intervall och försöker igen |
| kastar exception | **Propagerar direkt** — inga återförsök |
| `empty()` efter sista försöket | Kastar `RetriesExhaustedException` (checked) |

Antal anrop är `intervals.size() + 1`. `runWithRetries(Supplier<Boolean>, intervals)` är samma sak
för operationer utan returvärde.

> **Viktigt:** `RetryUtil` fångar *inga* exceptions (trots vad `FAULT_HANDLING_OVERVIEW.md` antyder).
> Fånga adapterns exception i suppliern och översätt till `Result.empty()` för transienta fel, annars
> hamnar du i ramverkets catch-all och får `RIMFROST_OTHER` utan återförsök.

Bestäm medvetet vilka fel som är **transienta** (5xx, timeout → `empty()`) och vilka som är
**svar** (404 → `of(null)`). I det här repot betyder 404 från arbetsgivare "ingen anställning",
vilket ger utfallet `UTREDNING` i stället för ett felsvar.

### 4.8 Separera bedömningen från I/O

> **Källor:** katt: `logic/KattSjukpenningBedomning.java`, `logic/KattSjukpenningService.java`,
> test: `KattSjukpenningBedomningTest.java`. Rekommendationen är ett designval i det här repot,
> inte ett krav från ramverket.

Lägg själva villkoren i en ren klass utan beroenden — det gör dem triviala att testa uttömmande och
gör att bedömningen kan sparas som underlag:

```java
public record KattSjukpenningBedomning(boolean kattRegistrerad, boolean veterinarintyg, boolean agareForsakrad)
{
   public Utfall utfall()
   {
      return kattRegistrerad && veterinarintyg && agareForsakrad ? Utfall.JA : Utfall.UTREDNING;
   }
}
```

Servicen blir då bara: hämta → bygg bedömning → `RegelUtils.createUnderlag("…Bedomning", 1,
bedomning, objectMapper)` → returnera `bedomning.utfall()`. Se
[`KattSjukpenningBedomning.java`](src/main/java/se/fk/github/maskinellregelkattsjukpenning/logic/KattSjukpenningBedomning.java).

### 4.9 Tester

> **Källor:** katt: `src/test/java/...` (alla testklasser), `src/test/resources/application.properties`,
> `config-test.yaml`, `mappings/*.json`; mask (test-JAR): `src/test/java/.../base/AbstractRegelMaskinell*Test.java`,
> `base/RegelMaskinellTestData.java`, `helpers/WireMockRegelMaskinell.java`; regel (test-JAR):
> `src/test/java/.../RegelTestBase.java`, `RegelKafkaConnector.java`, `WireMockHandlaggning.java`.
> Id:t `…1234` och specifikations-id:t kommer från `@CsvSource` i de abstrakta testklasserna.

Bygg testerna i tre lager — mönstret i `src/test`:

| Lager | Exempel | Kräver Quarkus |
|---|---|---|
| Ren logik | `KattSjukpenningBedomningTest` (alla 8 kombinationer), `KattUppgifterTest` | Nej |
| `processRegel` direkt | `KattSjukpenningProcessRegelTest` — anropar servicen med egen testdata, WireMock för adaptrar | Ja |
| Ramverkskontrakt via Kafka | `KattSjukpenningHandlaggningTest`, `KattSjukpenningResponseTest` | Ja |

**Ramverkets abstrakta testklasser** (från `rimfrost-framework-regel-maskinell:tests`). Ärv och
annotera — testerna följer med automatiskt:

| Klass | Verifierar | Hook att överskugga |
|---|---|---|
| `AbstractRegelMaskinellTest` | Bas: in-memory Kafka, WireMock-reset, `regelKafkaConnector`, `responsesTopic` | — |
| `AbstractRegelMaskinellResponseTest` | Svar med rätt `handlaggningId` och utfall `JA` för id `…1234` | — |
| `AbstractRegelMaskinellHandlaggningTest` | GET görs, PUT innehåller rätt yrkandestatus, `uppgiftStatus == null`, uppgiftspecifikations-id, underlag, producerade resultat | `createExpectedUnderlag()`, `createExpectedProduceradeResultat()` |
| `AbstractRegelMaskinellSequenceTest` | Ett GET, ett PUT, ett svar | — |

```java
@QuarkusTest
@QuarkusTestResource.List({ @QuarkusTestResource(WireMockMinRegel.class) })
public class MinRegelHandlaggningTest extends AbstractRegelMaskinellHandlaggningTest
{
   @Override
   protected ArrayList<Underlag> createExpectedUnderlag() { /* exakt det din regel skriver, i ordning */ }
}
```

Kontrakt som de abstrakta testerna förutsätter — uppfyll dem i dina testresurser:

- Handläggnings-id **`11111111-1111-1111-1111-111111111234`** ska ge utfall `JA`. WireMock-stubben
  matchar på suffixet (`/handlaggning/.+1234$`, se
  [`get-handlaggning-1234.json`](src/test/resources/mappings/get-handlaggning-1234.json)).
- `config-test.yaml` ska ha `specifikation.id: a42ffaed-2f20-47e8-8499-f2f79ae2f45f`.
- Underlag och producerade resultat jämförs **index för index** — ordningen spelar roll.

**WireMock för egna adaptrar.** `WireMockHandlaggning` startar en server på dynamisk port, läser
`src/test/resources/mappings/*.json` och returnerar properties som Quarkus använder. Utöka med dina
base-urls ([`WireMockKattSjukpenningMaskinell.java`](src/test/java/se/fk/github/maskinellregelkattsjukpenning/WireMockKattSjukpenningMaskinell.java)):

```java
public class WireMockMinRegel extends WireMockRegelMaskinell
{
   @Override
   protected Map<String, String> wiremockMapping(WireMockServer server)
   {
      Map<String, String> map = new HashMap<>(super.wiremockMapping(server));
      map.put("folkbokford.api.base-url", server.baseUrl());
      return map;
   }
}
```

Styr scenarier med personnummer och WireMock-`priority` (lägre = vinner): en generell stubbe med
`priority: 100` och specifika med `priority: 1`, t.ex. `arbetsgivare-persnr-9999-500.json` för
att testa uttömda återförsök.

**Test-`application.properties`** — det viktigaste:

```properties
application.config.path=src/test/resources/config-test.yaml
rimfrost.framework.regel.maskinell.retry.intervals=1      # annars tar feltesterna timmar
quarkus.http.test-port=0
mp.messaging.incoming.regel-requests.connector=smallrye-in-memory
mp.messaging.outgoing.regel-responses.connector=smallrye-in-memory
quarkus.kafka.devservices.enabled=false
kafka.source=MinRegelTest
```

Kör allt med:

```bash
./mvnw -s settings.xml clean verify
```

### 4.10 Container

> **Källor:** katt: `src/main/docker/Dockerfile.jvm`, `pom.xml` (`quarkus-smallrye-health`); regel:
> `integration/config/RegelConfigProviderYaml.java` (fallback till classpath).

[`Dockerfile.jvm`](src/main/docker/Dockerfile.jvm) kopierar `config.yaml` till `/deployments/` och
sätter `REGEL_CONFIG_PATH=/deployments/config.yaml`. Utan den raden faller laddningen tillbaka på
classpath, vilket fungerar men döljer att sökvägen är fel. Hälsokontroller kommer från
`quarkus-smallrye-health` (`/q/health`).

---

## 5. Manuell regel steg för steg

### 5.1 Livscykel

> **Källor:** man: `logic/RegelManuellRequestHandler.java` (`handleRegelRequest`,
> `handleUppgiftDone`), `logic/RegelManuellMiddlewareService.java` (`read`, `update`, `done`),
> `logic/RegelManuellServiceBase.java`; roul: `logic/OulUppgiftService.java` (`createOulUppgift`,
> `handleOulStatus`, orphan-städning), `logic/RegelOulCancelledService.java`; core:
> `sequences/ratt_till_forsakring_manuell.mmd`, `ASYNC_ROUTING.md` (`sub_topic`/`reply_topic`).

En manuell regel lever över flera anrop och behöver därför spara korrelationsdata i en databas:

```
 Kafka: RegelRequest ─► RegelManuellRequestHandler.handleRegelRequest
                        ├─ GET handläggning
                        ├─ slå upp erbjudandenamn (ErbjudandeReferensdataInterface)
                        └─ OulUppgiftService.createOulUppgift
                           ├─ POST OUL /uppgifter  (url = uppgift.path, sub_topic = kafka.subtopic, reply_topic = replyTo)
                           ├─ PUT handläggning med ny Uppgift (handläggningens version + 1)
                           └─ spara cloud_event_data, process_topic_info, common_data
                           (fel efter skapandet → uppgiften avslutas och raderna städas bort)

 Kafka: OUL-status ───► OulUppgiftService.handleOulStatus   (tilldelad, planerad …)
                        └─ uppdatera Uppgift i DB och i handläggningen (handläggningens version oförändrad)

 Portal: GET /{id} ───► Middleware.read → SID-kontroll → din readData(handlaggning)
 Portal: PATCH /{id} ─► Middleware.update → din updateData(handlaggning, request) → PUT handläggning
 Portal: POST /{id}/done ► Middleware.done → din done(handlaggningId)
                        └─ sendRegelResponse(id, utfall) → RegelManuellRequestHandler.handleUppgiftDone
                           ├─ läs korrelationsdata + handläggning   (fel → HTTP 5xx, inget svar skickas)
                           ├─ avsluta OUL-uppgiften                  (404 ignoreras, annat fel → HTTP 5xx)
                           ├─ RegelResponse(utfall) till sparad replyTo
                           ├─ radera korrelationsdata
                           └─ PUT handläggning: Uppgift AVSLUTAD + utfordTs (fel loggas bara)

 Kafka: cancelled ────► RegelOulCancelledService  (t.ex. BPMN-timeout)
                        ├─ din RegelOulCancelledHandler, om den finns
                        ├─ avsluta OUL-uppgiften (best effort)
                        └─ radera korrelationsdata
```

Sekvensdiagram finns i `rimfrost/sequences/ratt_till_forsakring_manuell.mmd`.

### 5.2 Klasser du skriver

> **Källor:** man: `logic/RegelManuellServiceInterface.java`, `logic/RegelManuellServiceBase.java`,
> `presentation/rest/RegelManuellController.java`, `logic/RegelManuellMiddlewareService.java`
> (abstrakt), `README.md` (avsnitt Implementera en manuell regel); man (tester, som visar hur
> klasserna kopplas ihop): `logic/RegelManuellTestService.java`,
> `presentation/rest/RegelManuellTestController.java`, `logic/RegelManuellMiddlewareServiceTest.java`
> (den konkreta middleware-bönan); roul: `logic/RegelOulCancelledHandler.java`,
> `logic/RegelOulCancelledService.java`. Att middleware-subklassen krävs är en slutsats av att
> controllern injicerar gränssnittet och att ramverkets enda konkreta implementation ligger i testkoden.

En manuell regel består av **tre obligatoriska CDI-bönor** och en valfri. `T` är svarstypen för
`GET`, `Y` är request-typen för `PATCH` — normalt genererade från regelns OpenAPI-spec.

**1. Service** — affärslogiken:

```java
@ApplicationScoped
public class MinRegelService extends RegelManuellServiceBase
      implements RegelManuellServiceInterface<GetMinRegelResponse, PatchMinRegelRequest>
{
   @Inject OulUppgiftService oulUppgiftService;

   /** Underlaget handläggaren ser. Anropas bara om ingen individ har skyddad identitet. */
   @Override
   public GetMinRegelResponse readData(Handlaggning handlaggning) { … }

   /** Handläggarens inmatning → det som ska persisteras. Ramverket gör PUT med det du returnerar. */
   @Override
   public HandlaggningUpdate updateData(Handlaggning handlaggning, PatchMinRegelRequest request)
   {
      return ImmutableHandlaggningUpdate.builder()
            .id(handlaggning.id())
            .version(handlaggning.version())                       // stega om ändringen är meningsfull
            .yrkande(RegelUtils.createYrkandeWithUpdatedProduceradeResultat(handlaggning.yrkande(), andrade))
            .processInstansId(handlaggning.processInstansId())
            .skapadTS(handlaggning.skapadTS())
            .avslutadTS(handlaggning.avslutadTS())
            .handlaggningspecifikationId(handlaggning.handlaggningspecifikationId())
            .uppgift(oulUppgiftService.getCorrelationData(handlaggning.id()).uppgift())  // behåll aktuell uppgift
            .build();
   }

   /** Handläggaren är klar. Bestäm utfallet och lämna över till ramverket. */
   @Override
   public void done(UUID handlaggningId)
   {
      sendRegelResponse(handlaggningId, Utfall.JA);
   }
}
```

`RegelManuellServiceBase` ger dig `objectMapper` och `sendRegelResponse(handlaggningId, utfall)`,
som startar hela done-flödet i 5.1. **Anropa den exakt en gång i `done()`** — glömmer du den får
processen aldrig något svar och OUL-uppgiften blir kvar.

**2. REST-controller** — tom klass, alla ändpunkter ärvs:

```java
@ApplicationScoped
@Path("/regel/min-regel")          // samma värde som uppgift.path i config.yaml
public class MinRegelController extends RegelManuellController<GetMinRegelResponse, PatchMinRegelRequest>
{
}
```

| Ändpunkt | Metod | Gör |
|---|---|---|
| `/utokadUppgiftsbeskrivning` | GET | Returnerar `utokadUppgiftsbeskrivning.beskrivning` från `config.yaml` |
| `/{handlaggningId}` | GET | SID-kontroll → `readData()` → 200 med `T` |
| `/{handlaggningId}` | PATCH | Bean Validation (`@Valid @NotNull`) på `Y` → `updateData()` → PUT → 204 |
| `/{handlaggningId}/done` | POST | `done()` → 204 |

**3. Middleware** — konkret subklass av ramverkets abstrakta `RegelManuellMiddlewareService`. Den
nämns inte i ramverkets README, men controllern injicerar
`RegelManuellMiddlewareServiceInterface<T, Y>` och ramverket levererar ingen konkret böna:

```java
@ApplicationScoped
public class MinRegelMiddlewareService
      extends RegelManuellMiddlewareService<GetMinRegelResponse, PatchMinRegelRequest>
{
}
```

Saknas den får du `Unsatisfied dependency for type RegelManuellMiddlewareServiceInterface<…>` vid
uppstart.

**4. (Valfri) `RegelOulCancelledHandler`** — regelspecifik städning när processen avbryter flödet
(t.ex. timeout). Implementera `handleCancelled(UUID handlaggningId)` i en `@ApplicationScoped`-böna.
Fel loggas och stoppar inte ramverkets egen städning.

### 5.3 Skyddad identitet (SID)

> **Källor:** man: `logic/RegelManuellMiddlewareService.java` (`read`, `checkSid`,
> `unassignUppgift`, `extractIndivider`), `README.md`; roul: `logic/OulUppgiftService.java`
> (`tryUnassignOulUppgift`).

Vid varje `GET /{handlaggningId}` frågar middlewaren SID-tjänsten om **någon** individ i
`yrkande.individYrkandeRoller` har skyddad identitet. Om ja:

1. OUL-uppgiften avtilldelas (best effort, fel påverkar inte svaret) så att den kan plockas av en
   handläggare med rätt behörighet.
2. HTTP **403** `Skyddad identitet` returneras och `readData()` anropas inte.

`PATCH` och `done` gör ingen egen SID-kontroll — de förutsätter att portalen först gjort `GET`.

### 5.4 Konfiguration

> **Källor:** man: `src/main/resources/application.properties`, `README.md` (`sid.api.base-url`),
> `logic/RegelManuellRequestHandler.java` (`kafka.source`, responses-topic,
> `ErbjudandeReferensdataInterface`); roul: `README.md` (avsnitt Konfiguration),
> `src/main/resources/application.properties` (datasource, Flyway, `kafka.cancelled.topic`,
> `oul.api.base-url`), `logic/OulUppgiftService.java` (`kafka.subtopic`); oul:
> `src/main/resources/application.properties` (status-kanalen); core: `ASYNC_ROUTING.md` (scenario 2);
> regel (test-JAR): `ErbjudandeReferensdataTestService.java`; mask: `pom.xml`
> (`rimfrost-referensdata-erbjudande`). `index-dependency` för manuell-ramverket är härlett från hur
> katt indexerar maskinell-ramverket.

`application.properties` för en manuell regel samlar krav från fyra ramverk:

```properties
# --- Indexering ---
quarkus.index-dependency.rimfrost-framework-regel-manuell.group-id=se.fk.rimfrost.framework.regel.manuell
quarkus.index-dependency.rimfrost-framework-regel-manuell.artifact-id=rimfrost-framework-regel-manuell
# (regel, regel-oul, oul, sid och handläggningsadaptern indexeras av ramverkens egna properties)

# --- Kafka: regelförfrågan och svar ---
kafka.source=RegelMinRegelManuell
mp.messaging.incoming.regel-requests.topic=min-regel-manuell-requests
mp.messaging.outgoing.regel-responses.topic=min-regel-manuell-responses

# --- Kafka: OUL-statusnotifieringar — de två raderna MÅSTE matcha ---
kafka.subtopic=min-regel-manuell
mp.messaging.incoming.operativt-uppgiftslager-status-notification.topic=operativt-uppgiftslager-status-notification.min-regel-manuell

# --- Kafka: avbrutna flöden (krävs — kanalen regel-oul-cancelled läser ${kafka.cancelled.topic}) ---
kafka.cancelled.topic=<topic som processen publicerar cancelled-events på>

# --- REST ---
handlaggning.api.base-url=http://rimfrost-k8s-handlaggning:8080
oul.api.base-url=http://rimfrost-k8s-uppgiftslager:8080
sid.api.base-url=http://<sid-tjänst>:8080

# --- Databas (korrelationsdata) ---
quarkus.datasource.jdbc.url=jdbc:postgresql://<host>:5432/<db>
quarkus.datasource.username=<användare>
quarkus.datasource.password=<lösenord>
quarkus.flyway.default-schema=min_regel_manuell
regel.persistence.table-prefix=min_regel_manuell      # unikt per regel, annars startfel

# --- Regelmetadata ---
application.config.path=src/main/resources/config.yaml
```

Ramverket `rimfrost-framework-regel-oul` sätter redan `db-kind=postgresql`, Flyway
`migrate-at-start`, `hibernate-orm.schema-management.strategy=validate` och
`RegelPhysicalNamingStrategy`.

**Erbjudandenamn.** `RegelManuellRequestHandler` injicerar `ErbjudandeReferensdataInterface` för att
sätta erbjudandets namn på OUL-uppgiften. Ramverket innehåller ingen produktionsimplementation —
regeln måste ha en på classpath (t.ex. `se.fk.rimfrost.referensdata:rimfrost-referensdata-erbjudande`
som maskinell-ramverket drar in, med `referensdata.api.base-url`). I test finns
`ErbjudandeReferensdataTestService` som `@DefaultBean` i `rimfrost-framework-regel:tests`.

### 5.5 Databasmigrering

> **Källor:** roul: `docs/db-schema.md` (ramverket levererar inga migrationer),
> `src/test/resources/db/migration/oul/V001__framework_tables.sql` (SQL:en nedan),
> `storage/internal/CloudEventDataEntity.java`, `RegelCommonDataEntity.java`,
> `ProcessTopicInfoEntity.java` (kolumnerna), `storage/internal/RegelPhysicalNamingStrategy.java`
> (prefix), `src/main/resources/application.properties` (`schema-management.strategy=validate`).
> Att `db-schema.md` är inaktuell är en slutsats av att dess kolumner skiljer sig från entiteterna.

Ramverket levererar **inga** Flyway-migrationer — regeln skapar tabellerna själv i
`src/main/resources/db/migration/`. Eftersom Hibernate körs med `validate` måste kolumnerna matcha
entiteterna exakt. Använd den här (hämtad från ramverkets egen testmigrering, som matchar
entiteterna) och byt `<prefix>` mot värdet i `regel.persistence.table-prefix`:

```sql
-- V1__regel_oul_tables.sql
CREATE TABLE <prefix>_cloud_event_data (
    handlaggning_id      UUID         NOT NULL PRIMARY KEY,
    event_id             UUID         NOT NULL,
    kogitorootprociid    UUID,
    kogitoparentprociid  UUID,
    kogitoprocinstanceid UUID,
    kogitorootprocid     VARCHAR(255),
    kogitoprocid         VARCHAR(255),
    kogitoprocist        VARCHAR(255),
    kogitoprocversion    VARCHAR(255),
    type                 VARCHAR(255),
    source               VARCHAR(255),
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE <prefix>_common_data (
    handlaggning_id                UUID         NOT NULL PRIMARY KEY,
    uppgift_id                     UUID,
    uppgift_version                INTEGER      NOT NULL DEFAULT 0,
    uppgift_skapad_ts              TIMESTAMPTZ,
    uppgift_utford_ts              TIMESTAMPTZ,
    uppgift_planerad_ts            TIMESTAMPTZ,
    uppgift_utforar_id_typ_id      VARCHAR(255),
    uppgift_utforar_id_varde       VARCHAR(255),
    uppgift_status                 VARCHAR(255),
    uppgift_aktivitet_id           UUID,
    uppgift_fssa_information       VARCHAR(255),
    uppgift_specifikation_id       UUID,
    uppgift_specifikation_version  INTEGER,
    oul_uppgift_id                 UUID,
    version                        BIGINT       NOT NULL DEFAULT 0,
    created_at                     TIMESTAMPTZ  NOT NULL,
    updated_at                     TIMESTAMPTZ  NOT NULL
);

CREATE TABLE <prefix>_process_topic_info (
    handlaggning_id  UUID         NOT NULL PRIMARY KEY,
    reply_topic      VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
```

> `rimfrost-framework-regel-oul/docs/db-schema.md` visar en **äldre** kolumnuppsättning som inte
> längre matchar entiteterna. Följer du den startar tjänsten inte (schema-validering).

### 5.6 Felhantering i manuella regler

> **Källor:** man: `presentation/rest/RegelManuellExceptionMapper.java`, `ExceptionMapper.java`,
> `ValidationExceptionMapper.java`, `WebApplicationExceptionMapper.java`,
> `logic/RegelManuellMiddlewareService.java` (`toHttpStatus`), `logic/RegelManuellRequestHandler.java`
> (felsvar och done-ordningen); core: `FAULT_HANDLING_OVERVIEW.md` (avsnitt 2).

**Kafka-trigger** (`handleRegelRequest`): alla fel fångas, eventuellt skapad OUL-uppgift avslutas
och ett felsvar skickas till `replyTo` (`RIMFROST_HANDLAGGNING_READ_FAILURE` eller
`RIMFROST_OTHER`). Inga återförsök.

**REST-trigger**: undantag mappas till HTTP-svar med `ErrorResponse { code, message }`:

| Undantag | HTTP |
|---|---|
| `RegelManuellException(status, …)` | `status` — kasta den själv från din service för kontrollerade fel |
| `HandlaggningException` / `SidException` NOT_FOUND / BAD_REQUEST / SERVICE_UNAVAILABLE | 404 / 400 / 503 |
| Versionskonflikt vid PATCH | 500 ("Programming fault in regel service?") |
| `ValidationException` (ogiltig PATCH-body) | 400 |
| `WebApplicationException` | Dess egen status |
| Övrigt | 500 (meddelande styrs av `rimfrost.framework.regel.manuell.rest.exception.catchall.response.message`) |

Done-flödet är byggt så att **`POST /done` kan göras om**: allt som kan misslyckas före
Kafka-svaret ger 5xx utan sidoeffekter, och efter att svaret skickats loggas fel bara.

### 5.7 Tester

> **Källor:** man (test-JAR): `src/test/java/.../base/AbstractRegelManuell*Test.java`,
> `helpers/WireMockRegelManuell.java`; man: `docs/test-strategy.md`, `README.md` (avsnitt Test-JAR),
> `src/test/resources/application.properties`.

Ta med `rimfrost-framework-regel-manuell:tests` och `rimfrost-framework-regel:tests` i
`test`-scope. Abstrakta testklasser:

| Klass | Verifierar |
|---|---|
| `AbstractRegelManuellTest` | Bas: in-memory Kafka, WireMock, **mockad `OulUppgiftService`**, REST-hjälpare |
| `AbstractRegelManuellHandlaggningTest` | GET vid förfrågan; PUT med `AVSLUTAD` och `utfordTs` efter `/done` |
| `AbstractRegelManuellOulTest` | OUL-specen: regelnamn, beskrivning, roll, url, `replyTo`, CloudEvent-attribut |
| `AbstractRegelManuellResponseTest` | Utfall och handläggnings-id i svaret efter `/done` |
| `AbstractRegelManuellUtokadUppgiftsbeskrivningTest` | `GET /utokadUppgiftsbeskrivning` |

```java
@QuarkusTest
@QuarkusTestResource.List({ @QuarkusTestResource(WireMockRegelManuell.class) })
public class MinRegelResponseTest extends AbstractRegelManuellResponseTest
{
}
```

Det som skiljer sig från maskinella tester:

- `OulUppgiftService` mockas med `@InjectMock` — databasen och OUL-klienten testas i
  `rimfrost-framework-regel-oul`, inte här. `getCorrelationData(any())` stubbas om före varje test.
- Testerna anropar REST-ändpunkterna under property **`regel.manuell.base-path`** — sätt den till
  samma värde som controllerns `@Path`.
- Efter `sendRegelRequest` måste testet vänta på konsumenttråden med
  `waitForRegelRequestProcessed(handlaggningId)` innan `/done` anropas.
- Stäng av databasen i test:

```properties
regel.manuell.base-path=/regel/min-regel
regel.persistence.table-prefix=min_regel_manuell
quarkus.flyway.enabled=false
quarkus.hibernate-orm.active=false
quarkus.datasource.devservices.enabled=false
quarkus.kafka.devservices.enabled=false
mp.messaging.incoming.regel-requests.connector=smallrye-in-memory
mp.messaging.outgoing.regel-responses.connector=smallrye-in-memory
mp.messaging.outgoing.regel-responses.topic=min-regel-manuell-responses
kafka.source=MinRegelTest
kafka.subtopic=min-regel-test
```

Jämför med `rimfrost-framework-regel-manuell/src/test/resources/application.properties`.

---

## 6. Referens: interna paket och klasser

> **Källor:** Varje tabell bygger på källfilerna i respektive repo under den angivna paketsökvägen
> (`src/main/java/se/fk/rimfrost/framework/...`), och test-JAR-raderna på `src/test/java/...`.
> Undantag: adaptrarnas källkod har inte granskats lokalt — adaptertabellen är härledd från katt:
> `logic/KattSjukpenningService.java`, test: `KattSjukpenningHandlaggningTest.java` (fälten i
> `ArbetsgivareResponse`), `src/test/resources/mappings/*.json` (sökvägar) samt från användningen av
> `HandlaggningAdapter` och `SidAdapter` i regel, mask och man.

Endast klasser som en regelutvecklare rör eller behöver förstå. "Du" = regelimplementationen.

### `rimfrost-framework-regel` — `se.fk.rimfrost.framework.regel`

| Klass | Paket | Roll |
|---|---|---|
| `RegelConsumer` | `presentation.kafka` | `@Incoming("regel-requests")`, `@Blocking`. Delegerar till `RegelMessageHandler` |
| `RegelMessageHandler` | `presentation.kafka` | Sätter MDC, mappar payload → `RegelDataRequest`, anropar `RegelRequestHandlerInterface` |
| `RegelRequestHandlerInterface` | `presentation.kafka` | `handleRegelRequest(RegelDataRequest)`. Implementeras av maskinell/manuell-ramverket — **exakt en** böna får finnas |
| `RegelDataRequest` | `logic.dto` | `handlaggningId`, `aktivitetId`, `replyTo`, `type` + alla `kogito*`-fält |
| `CloudEventData` | `logic.entity` | Kogito-korrelationsfälten + `type`/`source` för svaret |
| `RegelRequestHandlerBase` | `logic` | Abstrakt bas med `createCloudEvent`, `sendResponse`, `sendErrorResponse`, `createUppgift`, `createRegelErrorInformation`, `getHandlaggning`/`updateHandlaggning` (kastar `RegelCancelledException`) |
| `RegelCancelledException` | `logic` | Bär `RegelErrorInformation`; signalerar "avbryt och skicka felsvar" |
| `RegelMapper` | `logic` | `CloudEventData` + utfall/fel → `RegelResponse` |
| `RegelUtils` | `logic` | Se [3.4](#34-regelutils--hjälpmetoder) |
| `RegelConfig` (+ `Uppgift`, `Specifikation`, `Regel`, `Lagrum`, `UtokadUppgiftsbeskrivning`) | `logic.config` | POJO:er för `config.yaml` |
| `RegelConfigProvider` / `RegelConfigProviderYaml` | `integration.config` | `getConfig()`. Injicera `RegelConfigProvider` om din regel behöver metadata |
| `YamlConfigLoader` | `integration.config` | Schemavalidering + SnakeYAML-parsning |
| `RegelKafkaProducer` | `integration.kafka` | `sendRegelResponse(RegelResponse, topic)` — topic sätts per meddelande |

**Test-JAR:** `RegelTestBase` (in-memory Kafka, `mapper`, `regelKafkaConnector`, läser
`test.properties`), `KafkaConnector`/`RegelKafkaConnector` (`sendRegelRequest(id, replyTo)`,
`waitForRegelResponse()`), `RegelTestData` (standard-payload), `WireMockHandlaggning`
(`waitForHandlaggningRequests`, `getLastPutHandlaggning`, `getUppgiftFromLastPutHandlaggning`),
`ErbjudandeReferensdataTestService`.

### `rimfrost-framework-regel-maskinell` — `se.fk.rimfrost.framework.regel.maskinell`

| Klass | Paket | Roll |
|---|---|---|
| `RegelMaskinellServiceInterface` | `logic` | **Du implementerar:** `RegelMaskinellResult processRegel(RegelMaskinellRequest)` |
| `RegelMaskinellRequestHandler` | `logic` | Orkestreraren i [4.1](#41-vad-ramverket-gör-åt-dig) |
| `RegelMaskinellMapper` | `logic` | `toRegelMaskinellRequest(...)`, `toHandlaggningUpdate(...)` (behåller version) |
| `HandlaggningUpdateConflictException` | `logic` | Intern — avbryter återförsök vid versionskonflikt |
| `RegelMaskinellRequest` | `logic.dto` | `handlaggning`, `uppgift`, `processInstansId` |
| `RegelMaskinellResult` | `logic.dto` | Sealed: `RegelMaskinellSuccessResult` (`utfall`, `handlaggningUpdate`) eller `RegelMaskinellErrorResult` (`regelErrorInformation`) |
| `RetryUtil` | `logic.helpers.retry` | `getWithRetries(Supplier<Result<T>>, intervals)`, `runWithRetries(Supplier<Boolean>, intervals)` |
| `Result<T>` | `logic.helpers.retry` | `of(värde)` = klart, `empty()` = försök igen |
| `RetriesExhaustedException` | `logic.helpers.retry` | Checked; alla försök förbrukade |

**Property:** `rimfrost.framework.regel.maskinell.retry.intervals` (sekunder, kommaseparerad lista).

### `rimfrost-framework-regel-manuell` — `se.fk.rimfrost.framework.regel.manuell`

| Klass | Paket | Roll |
|---|---|---|
| `RegelManuellServiceInterface<T, Y>` | `logic` | **Du implementerar:** `readData`, `updateData`, `done` |
| `RegelManuellServiceBase` | `logic` | **Du ärver:** `objectMapper`, `sendRegelResponse(id, utfall)` |
| `RegelManuellMiddlewareService<T, Y>` | `logic` | Abstrakt. **Du gör en konkret subklass.** Handläggning-läsning/skrivning, SID, avtilldelning |
| `RegelManuellController<T, Y>` | `presentation.rest` | Abstrakt. **Du ärver och sätter `@Path`** |
| `RegelManuellRequestHandler` | `logic` | Kafka-förfrågan → OUL-uppgift; `handleUppgiftDone` → svar + städning |
| `RegelManuellUppgiftDoneHandler` | `logic` | Gränssnittet `sendRegelResponse` anropar |
| `RegelManuellException` | `logic` | `(Response.Status, meddelande[, orsak])` → HTTP-svar |
| `*ExceptionMapper` | `presentation.rest` | Se tabellen i [5.6](#56-felhantering-i-manuella-regler) |

### `rimfrost-framework-regel-oul` — `se.fk.rimfrost.framework.regel.oul`

| Klass | Paket | Roll |
|---|---|---|
| `OulUppgiftService` | `logic` | `createOulUppgift(spec)`, `endOulUppgift`/`tryEndOulUppgift`, `unassignOulUppgift`/`tryUnassignOulUppgift`, `getCorrelationData(id)` (null om någon rad saknas), `cleanupCorrelation(id)`, `handleOulStatus(status)` |
| `OulUppgiftSpec` | `logic.entity` | Indata till `createOulUppgift` — byggs av manuell-ramverket från `config.yaml` |
| `OulCorrelationData` | `logic.entity` | `oulUppgiftId`, `uppgift`, `replyTopic`, `cloudEventData` |
| `RegelOulCancelledHandler` | `logic` | **Valfri hook:** `handleCancelled(UUID)` |
| `RegelOulCancelledService` / `RegelOulCancelledConsumer` | `logic` / `presentation.kafka` | Kanal `regel-oul-cancelled` → städning |
| `OulServiceException` | `logic.exception` | `ErrorType` NOT_FOUND / SERVICE_UNAVAILABLE / … |
| `*Storage`, `storage.internal.*` | `storage` | Panache-repositories för de tre tabellerna. `RegelPhysicalNamingStrategy` lägger på prefixet |

`OulUppgiftService` implementerar `OulHandlerInterface` från `rimfrost-framework-oul` — det får bara
finnas **en** sådan böna, så implementera inte gränssnittet själv i en manuell regel.

### `rimfrost-framework-oul` — `se.fk.rimfrost.framework.oul`

Kafka-konsument på kanalen `operativt-uppgiftslager-status-notification`
(`OulKafkaConsumer` → `OulMessageHandler` → `OulHandlerInterface.handleOulStatus(OulStatus)`).
`OulStatus` bär `handlaggningId`, `uppgiftId`, `uppgiftStatus`, `utforarId`, `planeradTill` och
`processInfo` (med `replyTopic` och CloudEvent-attribut). Du konfigurerar bara topic-namnet.

### Adaptrar — `rimfrost-adapter-*`

REST-klienter med samma form; användningen i det här repot:

| Adapter | Anrop | Request / Response | Exception |
|---|---|---|---|
| `FolkbokfordAdapter` | `getFolkbokfordInfo(req)` | `ImmutableFolkbokfordRequest.personnummer` → `FolkbokfordResponse` | `FolkbokfordException.getErrorType()` (`NOT_FOUND` …) |
| `ArbetsgivareAdapter` | `getArbetsgivareInfo(req)` | `ImmutableArbetsgivareRequest.personnummer` → `ArbetsgivareResponse` (`organisationsnummer`, `organisationsnamn`, `arbetstidProcent`, `anstallningsdag`, `sistaAnstallningsdag`) | `ArbetsgivareException.getErrorCode()` (`ArbetsgivareErrorCode.NOT_FOUND` …) |
| `HandlaggningAdapter` | `readHandlaggning(id)`, `updateHandlaggning(update)` | Domänmodellen i [3.3](#33-handläggningens-domänmodell) | `HandlaggningException.getErrorType()` (`NOT_FOUND`, `BAD_REQUEST`, `CONFLICT`, `SERVICE_UNAVAILABLE` …) |
| `SidAdapter` | `containsSid(List<Idtyp>)` | → `boolean` | `SidException.getErrorType()` |

Varje adapter läser sin bas-URL från `<namn>.api.base-url` och måste indexeras (3.7). Observera att
folkbokförings- och arbetsgivaradaptrarna anropar `/folkbokforing/{pnr}` respektive
`/arbetsgivare/{pnr}` — det är de sökvägar dina WireMock-stubbar ska matcha.

---

## 7. Fallgropar och felsökning

> **Källor:** Sammanställning av fynden i kapitel 3–5; varje rad hänvisar till avsnittet där
> källan anges. Felmeddelandena är citerade ur `YamlConfigLoader.java`,
> `RegelPhysicalNamingStrategy.java` och `RegelManuellMiddlewareService.java`.

| Symptom | Trolig orsak | Åtgärd |
|---|---|---|
| `Config violates JSON Schema: …` vid start | Okänt/felstavat fält, fel typ (t.ex. `kapitel: "4"` — schemat kräver heltal, så inga citattecken), saknat obligatoriskt fält | Jämför med `regel_schema.yaml`; extra data under `metadata` |
| `Unsatisfied dependency for type …` | Saknad `quarkus.index-dependency`, saknad middleware-böna (manuell) eller saknad `ErbjudandeReferensdataInterface` (manuell) | Se 3.7, 5.2, 5.4 |
| Svar når aldrig processen | `replyTo` saknas i förfrågan, eller `done()` anropar inte `sendRegelResponse` | Logga `request.replyTo()`; se 3.1 och 5.2 |
| Felsvar `RIMFROST_OTHER` direkt, inga återförsök mot API | Adapterns exception läcker ut ur `RetryUtil`-suppliern | Fånga i suppliern → `Result.empty()` (4.7) |
| `RIMFROST_OTHER` trots try/catch runt underlag | `RegelUtils` kastar `InternalError` (ett `Error`) | Fånga `InternalError` uttryckligen |
| `RIMFROST_HANDLAGGNING_WRITE_FAILURE` "version conflict" | Fel `version` i `HandlaggningUpdate` | Utgå från lästa `handlaggning.version()`, se 3.5 |
| Underlag eller uppgift saknas efter PUT | Fält utelämnat i `HandlaggningUpdate` | Fyll i alla fält; utgå från `request.uppgift()` |
| Feltester tar evigheter | Produktionens retry-intervall i test | `rimfrost.framework.regel.maskinell.retry.intervals=1` |
| Abstrakta ramverkstester failar på utfall/underlag | Testdata för id `…1234` ger inte `JA`, eller fel ordning på underlag | Se kontrakten i 4.9 |
| Manuell regel startar inte: Hibernate schema validation | Tabeller skapade enligt `db-schema.md` | Använd SQL i 5.5 |
| `regel.persistence.table-prefix must be configured` | Prefix saknas (även i test) | Sätt property |
| OUL-status kommer aldrig fram | `kafka.subtopic` och status-topic matchar inte | `…status-notification.<subtopic>` (5.4) |
| `GET /utokadUppgiftsbeskrivning` ger 500 | Sektionen saknas i `config.yaml` | Lägg till `utokadUppgiftsbeskrivning` |
| Manuell GET ger 403 | Någon individ har skyddad identitet — avsiktligt | Se 5.3 |
| Test-port krockar | Fast `quarkus.http.test-port` | Sätt `quarkus.http.test-port=0` |

---

## 8. Checklistor

> **Källor:** Sammanfattning av kapitel 4 och 5; frontend-templates från core: `FRAMEWORK.md`.

### Ny maskinell regel

- [ ] Repo från `rimfrost-template-regel-maskinell` (eller kopia av det här repot), nya Maven-koordinater
- [ ] `config.yaml` med **nya** UUID:er; validerar mot schemat
- [ ] `application.properties`: topics, `kafka.source`, base-urls, `quarkus.index-dependency` för ramverk + adaptrar
- [ ] `@ApplicationScoped` service som implementerar `RegelMaskinellServiceInterface`
- [ ] Varje externt anrop i `RetryUtil`, med medvetet val av `of(null)` vs `empty()`
- [ ] Bedömningen i en ren klass; sparas som underlag tillsammans med indata
- [ ] `HandlaggningUpdate` med alla fält, `uppgift.utfordTs` och genomtänkt `version`
- [ ] Regelspecifika felkoder med prefix; felmeddelanden med id:n
- [ ] Tester: ren logik, `processRegel`, `AbstractRegelMaskinell{Handlaggning,Response}Test`, feltester för uttömda återförsök
- [ ] Dockerfile kopierar `config.yaml` och sätter `REGEL_CONFIG_PATH`
- [ ] README med villkor, källor, underlag och felkoder

### Ny manuell regel

- [ ] Repo från `rimfrost-template-regel-manuell`; OpenAPI-spec för `T` (GET) och `Y` (PATCH)
- [ ] `config.yaml` med `uppgift.path` och `utokadUppgiftsbeskrivning`
- [ ] Service (`RegelManuellServiceBase` + `RegelManuellServiceInterface<T, Y>`) som anropar `sendRegelResponse` i `done()`
- [ ] Controller med `@Path` = `uppgift.path`
- [ ] Konkret `RegelManuellMiddlewareService<T, Y>`-böna
- [ ] Implementation av `ErbjudandeReferensdataInterface` på classpath
- [ ] Properties: `kafka.subtopic` + matchande status-topic, `kafka.cancelled.topic`, `oul`/`sid`/`handlaggning`-URL:er, datasource, `regel.persistence.table-prefix`
- [ ] Flyway-migrering enligt 5.5
- [ ] Ev. `RegelOulCancelledHandler`
- [ ] Tester med `AbstractRegelManuell*Test` och `regel.manuell.base-path`
- [ ] Micro-frontend och BFF (`rimfrost-template-micro-fe`, `rimfrost-template-micro-fe-bff`)

---

## 9. Vidare läsning

| Dokument | Innehåll |
|---|---|
| `rimfrost/README.md`, `rimfrost/FRAMEWORK.md` | Ekosystemet och beroendegrafen |
| `rimfrost/regler/CONFIG_YAML.md` | Fälten i `config.yaml` |
| `rimfrost/regler/VERSIONING.md` | Versionsfält i detalj |
| `rimfrost/ASYNC_ROUTING.md` | `replyTo`, `sub_topic`, `reply_topic` |
| `rimfrost/FAULT_HANDLING_OVERVIEW.md` | Ansvarsfördelning vid fel |
| `rimfrost/sequences/*.mmd` | Sekvensdiagram för maskinella och manuella flöden |
| `rimfrost-framework-*/docs/krav.md` | Kravspecifikation per ramverk (krav-id:n syns i testernas `@DisplayName`) |
| [README.md](README.md) | Den här regelns villkor, flöde och konfiguration |

---

## 10. Källförteckning

Guiden bygger på följande repon, granskade lokalt på angiven commit (gren `main`). Förkortningen
används i raderna **Källor:** ovan.

| Förk. | Repo | Commit | Javakällor relativt |
|---|---|---|---|
| core | [Forsakringskassan/rimfrost](https://github.com/Forsakringskassan/rimfrost/tree/2d2c60f) | `2d2c60f` (2026-10-12) | — (bara dokumentation) |
| regel | [Forsakringskassan/rimfrost-framework-regel](https://github.com/Forsakringskassan/rimfrost-framework-regel/tree/e33d933) | `e33d933` (2026-10-03) | `src/main/java/se/fk/rimfrost/framework/regel/` |
| mask | [Forsakringskassan/rimfrost-framework-regel-maskinell](https://github.com/Forsakringskassan/rimfrost-framework-regel-maskinell/tree/e67835b) | `e67835b` (2026-10-04) | `src/main/java/se/fk/rimfrost/framework/regel/maskinell/` |
| man | [Forsakringskassan/rimfrost-framework-regel-manuell](https://github.com/Forsakringskassan/rimfrost-framework-regel-manuell/tree/d6d3987) | `d6d3987` (2026-10-13) | `src/main/java/se/fk/rimfrost/framework/regel/manuell/` |
| roul | [Forsakringskassan/rimfrost-framework-regel-oul](https://github.com/Forsakringskassan/rimfrost-framework-regel-oul/tree/9ee4bce) | `9ee4bce` (2026-10-12) | `src/main/java/se/fk/rimfrost/framework/regel/oul/` |
| oul | [Forsakringskassan/rimfrost-framework-oul](https://github.com/Forsakringskassan/rimfrost-framework-oul/tree/3fb3003) | `3fb3003` (2026-10-03) | `src/main/java/se/fk/rimfrost/framework/oul/` |
| katt | det här repot | `f2745d9` (2026-10-15) | `src/main/java/se/fk/github/maskinellregelkattsjukpenning/` |

Sökvägar som börjar med `src/`, `docs/` eller är ett filnamn i repots rot (`README.md`, `pom.xml`)
är relativa till repots rot. Övriga Java-sökvägar (t.ex. `logic/RegelUtils.java`) är relativa till
katalogen i sista kolumnen. "test:" framför en sökväg betyder motsvarande katalog under `src/test/java/`.

**Inte granskade lokalt** (uppgifter om dem är härledda från hur de används):
`rimfrost-framework-regel-asyncapi` (`Utfall`, `RegelErrorInformation`, Kafka-DTO:er),
`rimfrost-framework-handlaggning-adapter` (domänmodellen), `rimfrost-framework-regel-error-codes`
(`RegelFelkod`), `rimfrost-framework-sid-adapter`, `rimfrost-framework-oul-adapter`,
`rimfrost-adapter-folkbokford`, `rimfrost-adapter-arbetsgivare`, `rimfrost-referensdata-erbjudande`
och template-repona `rimfrost-template-regel-*`.

Versionsnumren i kapitel 2 kommer från core: `bom/rimfrost-1_2-bom/rimfrost.md` och kan skilja sig
från vad `main` deklarerar (`*-SNAPSHOT`).
