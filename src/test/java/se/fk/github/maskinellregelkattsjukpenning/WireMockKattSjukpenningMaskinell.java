package se.fk.github.maskinellregelkattsjukpenning;

import com.github.tomakehurst.wiremock.WireMockServer;
import se.fk.rimfrost.framework.regel.maskinell.helpers.WireMockRegelMaskinell;
import java.util.HashMap;
import java.util.Map;

public class WireMockKattSjukpenningMaskinell extends WireMockRegelMaskinell
{
   @Override
   protected Map<String, String> wiremockMapping(WireMockServer server)
   {
      Map<String, String> map = new HashMap<>(super.wiremockMapping(server));
      map.put("folkbokford.api.base-url", server.baseUrl());
      map.put("arbetsgivare.api.base-url", server.baseUrl());
      return map;
   }
}
