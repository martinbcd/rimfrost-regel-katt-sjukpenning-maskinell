package se.fk.github.maskinellregelkattsjukpenning;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fk.github.maskinellregelkattsjukpenning.logic.KattSjukpenningService;
import se.fk.rimfrost.framework.handlaggning.model.Underlag;
import se.fk.rimfrost.framework.regel.Utfall;
import se.fk.rimfrost.framework.regel.maskinell.base.AbstractRegelMaskinellTest;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellErrorResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellResult;
import se.fk.rimfrost.framework.regel.maskinell.logic.dto.RegelMaskinellSuccessResult;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static se.fk.github.maskinellregelkattsjukpenning.KattSjukpenningTestData.*;

@QuarkusTest
@QuarkusTestResource.List(
{
      @QuarkusTestResource(WireMockKattSjukpenningMaskinell.class)
})
public class KattSjukpenningProcessRegelTest extends AbstractRegelMaskinellTest
{
   private static final String PERSNR = "19990101-1234";

   @Inject
   KattSjukpenningService kattSjukpenningService;

   @ParameterizedTest
   @CsvSource(
   {
         // Alla villkor uppfyllda
         "19990101-1234, JA",
         // Ingen anställning, ägaren är inte försäkrad
         "19990101-2222, UTREDNING",
         // Inte folkbokförd, men folkbokföring är inget villkor
         "19990101-3333, JA"
   })
   void process_regel_should_return_correct_utfall_from_arbetsgivare(String persnr, Utfall expectedUtfall)
   {
      var result = kattSjukpenningService.processRegel(newRegelMaskinellRequest(persnr));

      assertEquals(expectedUtfall, successResult(result).utfall());
   }

   @Test
   void process_regel_should_return_utredning_when_kattregistrering_saknas()
   {
      var request = newRegelMaskinellRequest(PERSNR, List.of(veterinarintyg(YRKANDE_FROM, YRKANDE_TOM)));

      var result = kattSjukpenningService.processRegel(request);

      assertEquals(Utfall.UTREDNING, successResult(result).utfall());
   }

   @Test
   void process_regel_should_return_utredning_when_katt_registrerad_pa_annan_agare()
   {
      var request = newRegelMaskinellRequest(PERSNR,
            List.of(kattregistrering("19990101-5678"), veterinarintyg(YRKANDE_FROM, YRKANDE_TOM)));

      var result = kattSjukpenningService.processRegel(request);

      assertEquals(Utfall.UTREDNING, successResult(result).utfall());
   }

   @Test
   void process_regel_should_return_utredning_when_veterinarintyg_saknas()
   {
      var request = newRegelMaskinellRequest(PERSNR, List.of(kattregistrering(PERSNR)));

      var result = kattSjukpenningService.processRegel(request);

      assertEquals(Utfall.UTREDNING, successResult(result).utfall());
   }

   @Test
   void process_regel_should_return_utredning_when_veterinarintyg_inte_tacker_perioden()
   {
      var request = newRegelMaskinellRequest(PERSNR,
            List.of(kattregistrering(PERSNR), veterinarintyg(YRKANDE_FROM, YRKANDE_TOM.minusDays(3))));

      var result = kattSjukpenningService.processRegel(request);

      assertEquals(Utfall.UTREDNING, successResult(result).utfall());
   }

   @Test
   void process_regel_should_return_underlag()
   {
      var result = kattSjukpenningService.processRegel(newRegelMaskinellRequest(PERSNR));

      var underlag = successResult(result).handlaggningUpdate().underlag();
      assertEquals(List.of("Folkbokförd", "Arbetsgivare", "KattSjukpenningBedomning"),
            underlag.stream().map(Underlag::typ).toList());
      assertEquals("{\"kattRegistrerad\":true,\"veterinarintyg\":true,\"agareForsakrad\":true}",
            underlag.getLast().data());
   }

   @Test
   void process_regel_should_update_handlaggning_and_uppgift()
   {
      var request = newRegelMaskinellRequest(PERSNR);

      var result = kattSjukpenningService.processRegel(request);

      var handlaggningUpdate = successResult(result).handlaggningUpdate();
      assertEquals(request.handlaggning().version() + 1, handlaggningUpdate.version());
      assertEquals(request.processInstansId(), handlaggningUpdate.processInstansId());
      assertNotNull(handlaggningUpdate.uppgift().utfordTs());
   }

   @ParameterizedTest
   @CsvSource(
   {
         "19990102-9999, (?i)^Failed to read arbetsgivare response\\..*, KATT_SJUKPENNING_MASKINELL_ARBETSGIVARE_READ_ERROR",
         "19990103-9999, (?i)^Failed to read folkbokford response\\..*, KATT_SJUKPENNING_MASKINELL_FOLKBOKFORING_READ_ERROR"
   })
   void process_regel_should_return_error_result_due_to_retries_exhausted(String persnr, String expectedMsgRegex,
         String expectedErrorCode)
   {
      var result = kattSjukpenningService.processRegel(newRegelMaskinellRequest(persnr));

      assertInstanceOf(RegelMaskinellErrorResult.class, result);
      var regelErrorInformation = ((RegelMaskinellErrorResult) result).regelErrorInformation();
      assertEquals(expectedErrorCode, regelErrorInformation.getFelkod());
      assertTrue(regelErrorInformation.getFelmeddelande().matches(expectedMsgRegex));
   }

   private static RegelMaskinellSuccessResult successResult(RegelMaskinellResult result)
   {
      assertInstanceOf(RegelMaskinellSuccessResult.class, result);
      return (RegelMaskinellSuccessResult) result;
   }
}
