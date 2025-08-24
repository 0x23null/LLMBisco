package model;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Replacement for the previous LLM class that delegates predictions
 * to a local TensorFlow model saved in {@code sicbo_core.keras}.
 *
 * The class communicates with a small Python helper script that
 * loads the Keras model and writes a JSON result to stdout.  The
 * helper reads a JSON string from stdin with a field "history".
 */
public class LLM {

    private final Path scriptPath;

    public LLM() {
        // Path to the Python helper script located alongside the model
        this.scriptPath = Path.of("src", "main", "java", "model", "predict.py");
    }

    /**
     * Sends the input JSON to the Python script and returns whatever
     * JSON the script prints.  In case of any error, a default
     * SKIP response is returned.
     */
    public String getAnswer(String inputJson) {
        Process process = null;
        try {
            process = new ProcessBuilder("python", scriptPath.toString())
                    .redirectErrorStream(true)
                    .start();

            try (OutputStreamWriter w = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(inputJson);
                w.flush();
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }

            int code = process.waitFor();
            if (code == 0) {
                return sb.toString().trim();
            }
        } catch (Exception e) {
            // fall through to return default below
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return "{\"pick\":\"SKIP\"}"; // default fallback
    }
}
