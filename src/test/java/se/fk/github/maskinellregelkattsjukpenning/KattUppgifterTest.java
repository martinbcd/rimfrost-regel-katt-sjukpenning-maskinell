package se.fk.github.maskinellregelkattsjukpenning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.fk.github.maskinellregelkattsjukpenning.logic.KattUppgifter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.fk.github.maskinellregelkattsjukpenning.KattSjukpenningTestData.*;

public class KattUppgifterTest
{
   private static final String PERSNR = "19990101-1234";

   private final ObjectMapper objectMapper = new ObjectMapper();

   @Test
   void katt_registrerad_pa_sokande_should_be_registrerad()
   {
      var yrkande = newYrkande(PERSNR, List.of(kattregistrering(PERSNR)));

      assertTrue(KattUppgifter.isKattRegistreradPaAgare(yrkande, PERSNR, objectMapper));
   }

   @Test
   void katt_registrerad_pa_annan_agare_should_not_be_registrerad()
   {
      var yrkande = newYrkande(PERSNR, List.of(kattregistrering("19990101-5678")));

      assertFalse(KattUppgifter.isKattRegistreradPaAgare(yrkande, PERSNR, objectMapper));
   }

   @Test
   void saknad_kattregistrering_should_not_be_registrerad()
   {
      var yrkande = newYrkande(PERSNR, List.of(veterinarintyg(YRKANDE_FROM, YRKANDE_TOM)));

      assertFalse(KattUppgifter.isKattRegistreradPaAgare(yrkande, PERSNR, objectMapper));
   }

   @Test
   void kattregistrering_med_ogiltig_data_should_not_be_registrerad()
   {
      var yrkande = newYrkande(PERSNR, List.of(
            produceratResultat(KattUppgifter.KATTREGISTRERING, YRKANDE_FROM, YRKANDE_TOM, "inte json")));

      assertFalse(KattUppgifter.isKattRegistreradPaAgare(yrkande, PERSNR, objectMapper));
   }

   @Test
   void veterinarintyg_som_tacker_perioden_should_be_godkant()
   {
      var yrkande = newYrkande(PERSNR, List.of(veterinarintyg(YRKANDE_FROM.minusDays(2), YRKANDE_TOM.plusDays(2))));

      assertTrue(KattUppgifter.hasVeterinarintygForPerioden(yrkande));
   }

   @Test
   void veterinarintyg_som_borjar_efter_perioden_should_not_be_godkant()
   {
      var yrkande = newYrkande(PERSNR, List.of(veterinarintyg(YRKANDE_FROM.plusDays(1), YRKANDE_TOM)));

      assertFalse(KattUppgifter.hasVeterinarintygForPerioden(yrkande));
   }

   @Test
   void veterinarintyg_som_slutar_fore_perioden_should_not_be_godkant()
   {
      var yrkande = newYrkande(PERSNR, List.of(veterinarintyg(YRKANDE_FROM, YRKANDE_TOM.minusDays(1))));

      assertFalse(KattUppgifter.hasVeterinarintygForPerioden(yrkande));
   }

   @Test
   void saknat_veterinarintyg_should_not_be_godkant()
   {
      var yrkande = newYrkande(PERSNR, List.of(kattregistrering(PERSNR)));

      assertFalse(KattUppgifter.hasVeterinarintygForPerioden(yrkande));
   }
}
