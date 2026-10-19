package se.fk.github.maskinellregelkattsjukpenning;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import se.fk.github.maskinellregelkattsjukpenning.logic.KattSjukpenningBedomning;
import se.fk.rimfrost.framework.regel.Utfall;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class KattSjukpenningBedomningTest
{
   @ParameterizedTest
   @CsvSource(
   {
         "true, true, true, JA",
         "false, true, true, UTREDNING",
         "true, false, true, UTREDNING",
         "true, true, false, UTREDNING",
         "false, false, true, UTREDNING",
         "false, true, false, UTREDNING",
         "true, false, false, UTREDNING",
         "false, false, false, UTREDNING"
   })
   void utfall_should_be_ja_only_when_all_villkor_are_uppfyllda(boolean kattRegistrerad, boolean veterinarintyg,
         boolean agareForsakrad, Utfall expectedUtfall)
   {
      var bedomning = new KattSjukpenningBedomning(kattRegistrerad, veterinarintyg, agareForsakrad);

      assertEquals(expectedUtfall, bedomning.utfall());
   }
}
