package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.AllowedModel
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistLoaderTest {

  private fun loadFixture(): Result<List<AllowedModel>> {
    val stream =
      requireNotNull(javaClass.getResourceAsStream("/model_allowlist_fixture.json")) {
        "Fixture not found on classpath"
      }
    return AllowlistLoader.parse(stream, Gson())
  }

  @Test
  fun loadFromFixture_returnsListOfTwoModels() {
    val result = loadFixture()
    assertEquals(2, result.getOrThrow().size)
  }

  @Test
  fun loadFromFixture_allModelsHaveRequiredFields() {
    val models = loadFixture().getOrThrow()
    for (model in models) {
      assertTrue("name empty for ${model.modelId}", model.name.isNotEmpty())
      assertTrue("modelId empty", model.modelId.isNotEmpty())
      assertTrue("modelFile empty for ${model.modelId}", model.modelFile.isNotEmpty())
      assertTrue("commitHash empty for ${model.modelId}", model.commitHash.isNotEmpty())
      assertTrue("sizeInBytes not positive for ${model.modelId}", model.sizeInBytes > 0L)

      val cfg = model.defaultConfig
      assertNotNull("defaultConfig null for ${model.modelId}", cfg)
      assertNotNull("topK null for ${model.modelId}", cfg!!.topK)
      assertNotNull("temperature null for ${model.modelId}", cfg.temperature)
      assertNotNull("accelerators null for ${model.modelId}", cfg.accelerators)

      // Decision T8 guard: GPU must be tried first.
      val firstToken = cfg.accelerators!!.split(",")[0].trim().lowercase()
      assertEquals("first accelerator must be gpu for ${model.modelId}", "gpu", firstToken)
    }
  }
}
