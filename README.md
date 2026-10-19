# rimfrost-regel-katt-sjukpenning-maskinell

Maskinell regel som bedömer om en katt har rätt till sjukpenning. Regeln bygger på
[rimfrost-framework-regel-maskinell](https://github.com/Forsakringskassan/rimfrost-framework-regel-maskinell)
och följer samma mönster som `rimfrost-regel-rtf-maskinell`.

## Villkor

Utfallet blir `JA` om alla villkor är uppfyllda, annars `UTREDNING`.

| Villkor | Källa |
|---|---|
| Katten är registrerad med den sökande som ägare | Yrkandets producerade resultat av typ `KATTREGISTRERING` där `data.agare` är den sökandes personnummer |
| Det finns ett sjukintyg från veterinär för perioden | Yrkandets producerade resultat av typ `VETERINARINTYG` vars from/tom täcker yrkandets from/tom |
| Ägaren är försäkrad | Ägaren har en arbetsgivare enligt arbetsgivare-API:et |

Folkbokförd-API:et används för att hämta ägarens uppgifter till underlaget. Folkbokföring är inget villkor.

Exempel på producerade resultat som regeln läser:

```json
{ "typ": "KATTREGISTRERING", "from": "...", "tom": "...", "data": "{\"chipnummer\":\"752098100123456\",\"agare\":\"19990101-1234\"}" }
{ "typ": "VETERINARINTYG",   "from": "2025-01-01T00:00:00Z", "tom": "2025-01-10T00:00:00Z", "data": "{\"diagnos\":\"Förkylning\"}" }
```

## Flöde

1. Ramverket tar emot regelförfrågan på Kafka och hämtar handläggningen.
2. `KattSjukpenningService` hämtar ägarens uppgifter från folkbokförd- och arbetsgivare-API:erna. Båda anropen görs med återförsök enligt `rimfrost.framework.regel.maskinell.retry.intervals`.
3. Villkoren bedöms i `KattSjukpenningBedomning`.
4. Handläggningen uppdateras med underlagen `Folkbokförd`, `Arbetsgivare` och `KattSjukpenningBedomning`, och utfallet skickas på Kafka.

Om ett API inte svarar efter alla återförsök returneras ett felsvar med felkod
`KATT_SJUKPENNING_MASKINELL_FOLKBOKFORING_READ_ERROR` eller `KATT_SJUKPENNING_MASKINELL_ARBETSGIVARE_READ_ERROR`.

## Konfiguration

| Property | Beskrivning |
|---|---|
| `mp.messaging.incoming.regel-requests.topic` | `katt-sjukpenning-maskinell-requests` |
| `mp.messaging.outgoing.regel-responses.topic` | `katt-sjukpenning-maskinell-responses` |
| `handlaggning.api.base-url` | Handläggningstjänsten |
| `folkbokford.api.base-url` | Folkbokföringstjänsten |
| `arbetsgivare.api.base-url` | Arbetsgivartjänsten |
| `application.config.path` | Sökväg till `config.yaml` |

## Bygg och testa

Kräver JDK 21 samt miljövariablerna `GITHUB_ACTOR` och `GITHUB_TOKEN`, med läsrättighet till Försäkringskassans paket.

```bash
./mvnw -s settings.xml clean verify
```
