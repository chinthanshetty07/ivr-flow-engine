package in.chinthanshetty.ivr;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestSupport.class)
class CallApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestSupport.MutableClock clock;

    @Test
    void fullCallOverHttp() throws Exception {
        clock.set("2026-11-03T06:30:00Z");
        String body = mvc.perform(post("/calls").contentType(MediaType.APPLICATION_JSON).content("{\"caller\":\"9876543210\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode started = json.readTree(body);
        String id = started.get("callId").asText();

        mvc.perform(post("/calls/{id}/digits", id).contentType(MediaType.APPLICATION_JSON).content("{\"digits\":\"0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ended").value(true))
                .andExpect(jsonPath("$.actions[0].queue").value("support"));

        mvc.perform(get("/calls/{id}", id))
                .andExpect(jsonPath("$.outcome").value("TRANSFERRED"))
                .andExpect(jsonPath("$.caller").value("******3210"));
        mvc.perform(get("/calls/stats")).andExpect(jsonPath("$.TRANSFERRED").isNumber());
    }

    @Test
    void validationAndUnknownCalls() throws Exception {
        mvc.perform(post("/calls").contentType(MediaType.APPLICATION_JSON).content("{\"caller\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/calls/{id}/digits", "nope").contentType(MediaType.APPLICATION_JSON).content("{\"digits\":\"1\"}"))
                .andExpect(status().isNotFound());
    }
}
