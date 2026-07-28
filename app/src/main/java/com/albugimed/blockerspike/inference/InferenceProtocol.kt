package com.albugimed.blockerspike.inference

internal object InferenceProtocol {
    const val REQUEST_GENERATION = 1
    const val GENERATION_RESULT = 2
    const val CANCEL_GENERATION = 3

    const val KEY_REQUEST_ID = "request_id"
    const val KEY_MODEL_PATH = "model_path"
    const val KEY_PROMPT = "prompt"
    const val KEY_RAW_TEXT = "raw_text"
    const val KEY_BACKEND = "backend"
    const val KEY_LOAD_MILLIS = "load_millis"
    const val KEY_GENERATION_MILLIS = "generation_millis"
    const val KEY_PEAK_PSS_KB = "peak_pss_kb"
    const val KEY_ERROR = "error"
}
