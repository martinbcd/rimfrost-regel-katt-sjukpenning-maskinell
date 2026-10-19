package se.fk.github.maskinellregelkattsjukpenning;

import se.fk.github.maskinellregelkattsjukpenning.logic.KattUppgifter;
import se.fk.rimfrost.framework.handlaggning.model.*;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.ImmutableRegelMaskinellRequest;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class KattSjukpenningTestData
{
   public static final OffsetDateTime YRKANDE_FROM = OffsetDateTime.parse("2025-01-01T00:00:00Z");
   public static final OffsetDateTime YRKANDE_TOM = OffsetDateTime.parse("2025-01-10T00:00:00Z");

   public static ProduceratResultat kattregistrering(String agare)
   {
      return produceratResultat(KattUppgifter.KATTREGISTRERING, YRKANDE_FROM, YRKANDE_TOM,
            "{\"chipnummer\":\"752098100123456\",\"agare\":\"" + agare + "\"}");
   }

   public static ProduceratResultat veterinarintyg(OffsetDateTime from, OffsetDateTime tom)
   {
      return produceratResultat(KattUppgifter.VETERINARINTYG, from, tom, "{\"diagnos\":\"Förkylning\"}");
   }

   public static ProduceratResultat produceratResultat(String typ, OffsetDateTime from, OffsetDateTime tom, String data)
   {
      return ImmutableProduceratResultat.builder()
            .id(UUID.randomUUID())
            .version(1)
            .resultatFrom(from)
            .resultatTom(tom)
            .yrkandeStatus("YRKAT")
            .typ(typ)
            .data(data)
            .build();
   }

   public static Yrkande newYrkande(String persnr, List<ProduceratResultat> produceradeResultat)
   {
      var idTyp = ImmutableIdtyp.builder()
            .typId(UUID.randomUUID().toString())
            .varde(persnr)
            .build();

      var individ = ImmutableIndividYrkandeRoll.builder()
            .individ(idTyp)
            .yrkandeRollId(UUID.randomUUID().toString())
            .build();

      return ImmutableYrkande.builder()
            .id(UUID.randomUUID())
            .addIndividYrkandeRoller(individ)
            .erbjudandeId(UUID.randomUUID().toString())
            .version(1)
            .yrkandeDatum(OffsetDateTime.now())
            .yrkandeFrom(YRKANDE_FROM)
            .yrkandeTom(YRKANDE_TOM)
            .yrkandeStatus("UNDER_UTREDNING")
            .avsikt("avsikt")
            .produceradeResultat(produceradeResultat)
            .build();
   }

   /**
    * Skapar en request där katten är registrerad på den sökande och har veterinärintyg för hela perioden.
    */
   public static RegelMaskinellRequest newRegelMaskinellRequest(String persnr)
   {
      return newRegelMaskinellRequest(persnr,
            List.of(kattregistrering(persnr), veterinarintyg(YRKANDE_FROM, YRKANDE_TOM)));
   }

   public static RegelMaskinellRequest newRegelMaskinellRequest(String persnr, List<ProduceratResultat> produceradeResultat)
   {
      var handlaggning = ImmutableHandlaggning.builder()
            .id(UUID.randomUUID())
            .version(1)
            .yrkande(newYrkande(persnr, produceradeResultat))
            .processInstansId(UUID.randomUUID())
            .skapadTS(OffsetDateTime.now())
            .handlaggningspecifikationId(UUID.randomUUID())
            .build();

      var uppgiftSpecifikation = ImmutableUppgiftSpecifikation.builder()
            .id(UUID.randomUUID())
            .version(1)
            .build();

      var utforare = ImmutableIdtyp.builder()
            .typId(UUID.randomUUID().toString())
            .varde(UUID.randomUUID().toString())
            .build();

      var uppgift = ImmutableUppgift.builder()
            .id(UUID.randomUUID())
            .version(1)
            .skapadTs(OffsetDateTime.now())
            .utforarId(utforare)
            .uppgiftStatus(null)
            .aktivitetId(UUID.randomUUID())
            .fSSAinformation("HANDLAGGNING_PAGAR")
            .uppgiftSpecifikation(uppgiftSpecifikation)
            .build();

      return ImmutableRegelMaskinellRequest.builder()
            .handlaggning(handlaggning)
            .uppgift(uppgift)
            .processInstansId(UUID.randomUUID())
            .build();
   }
}
