package com.ultikits.testfixtures.conditionaltwoforms;

import com.ultikits.ultitools.annotations.ConditionalOnConfig;
import com.ultikits.ultitools.annotations.Service;

/** A conditional component whose path no config entity declares (#612, gate-1 R613-01). */
@Service
@ConditionalOnConfig(value = "config/twoforms.yml", path = "features.chat")
public class TwoFormsConditionalService {
}
