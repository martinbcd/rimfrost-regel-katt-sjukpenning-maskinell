package se.fk.github.maskinellregelkattsjukpenning;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import se.fk.rimfrost.framework.regel.maskinell.base.AbstractRegelMaskinellResponseTest;

@QuarkusTest
@QuarkusTestResource.List(
{
      @QuarkusTestResource(WireMockKattSjukpenningMaskinell.class)
})
public class KattSjukpenningResponseTest extends AbstractRegelMaskinellResponseTest
{
}
