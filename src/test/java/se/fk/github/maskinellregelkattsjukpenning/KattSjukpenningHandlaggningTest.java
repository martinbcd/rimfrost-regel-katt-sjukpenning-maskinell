package se.fk.github.maskinellregelkattsjukpenning;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import se.fk.rimfrost.framework.handlaggning.model.ImmutableUnderlag;
import se.fk.rimfrost.framework.handlaggning.model.Underlag;
import se.fk.rimfrost.framework.regel.maskinell.base.AbstractRegelMaskinellHandlaggningTest;
import se.fk.rimfrost.jaxrsspec.controllers.generatedsource.model.ProduceratResultat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@QuarkusTest
@QuarkusTestResource.List(
{
      @QuarkusTestResource(WireMockKattSjukpenningMaskinell.class)
})
public class KattSjukpenningHandlaggningTest extends AbstractRegelMaskinellHandlaggningTest
{

   @Override
   protected ArrayList<Underlag> createExpectedUnderlag()
   {
      var folkbokfordUnderlagData = "{\"id\":\"19990101-1234\",\"fornamn\":\"Lisa\",\"efternamn\":\"Tass\",\"kon\":\"KVINNA\",\"utdelningsadress\":\"Storgatan 75\",\"postnummer\":\"12345\",\"postort\":\"Orsa\",\"careOf\":\"-\"}";
      var arbetsgivareUnderlagData = "{\"organisationsnummer\":\"987654-3210\",\"organisationsnamn\":\"Demo AB\",\"arbetstidProcent\":100,\"anstallningsdag\":\"2021-07-01\",\"sistaAnstallningsdag\":null}";
      var bedomningUnderlagData = "{\"kattRegistrerad\":true,\"veterinarintyg\":true,\"agareForsakrad\":true}";
      return new ArrayList<>(List.of(
            ImmutableUnderlag.builder()
                  .typ("Folkbokförd")
                  .version(1)
                  .data(folkbokfordUnderlagData)
                  .build(),
            ImmutableUnderlag.builder()
                  .typ("Arbetsgivare")
                  .version(1)
                  .data(arbetsgivareUnderlagData)
                  .build(),
            ImmutableUnderlag.builder()
                  .typ("KattSjukpenningBedomning")
                  .version(1)
                  .data(bedomningUnderlagData)
                  .build()));
   }

   @Override
   protected List<ProduceratResultat> createExpectedProduceradeResultat()
   {
      List<ProduceratResultat> list = new ArrayList<>();

      ProduceratResultat r1 = new ProduceratResultat();
      r1.setId(UUID.fromString("66666666-6666-6666-6666-666666661234"));
      r1.setYrkandestatus("YRKAT");

      ProduceratResultat r2 = new ProduceratResultat();
      r2.setId(UUID.fromString("66666666-6666-6666-6666-666667771234"));
      r2.setYrkandestatus("YRKAT");

      list.add(r1);
      list.add(r2);

      return list;
   }
}
