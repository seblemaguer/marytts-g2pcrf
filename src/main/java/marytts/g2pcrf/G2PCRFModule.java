/**
 * Copyright 2000-2006 DFKI GmbH.
 * All Rights Reserved.  Use is subject to license terms.
 *
 * This file is part of MARY TTS.
 *
 * MARY TTS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package marytts.g2pcrf;

// Mary baseline
import marytts.modules.MaryModule;
import marytts.config.MaryConfiguration;
import marytts.modules.nlp.phonemiser.AllophoneSet;

// Exceptions
import marytts.MaryException;
import marytts.exceptions.MaryConfigurationException;

// Parsing
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.google.common.base.Splitter;

// Collections
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

// G2P CRF
import com.github.steveash.jg2p.model.CmuEncoderFactory;
import com.github.steveash.jg2p.SimpleEncoder;
import com.github.steveash.jg2p.model.CmuSyllabifierFactory;
import com.github.steveash.jg2p.syllchain.Syllabifier;

// Data
import marytts.data.Utterance;
import marytts.data.Sequence;
import marytts.data.Relation;
import marytts.data.SupportedSequenceType;
import marytts.data.utils.IntegerPair;
import marytts.data.item.linguistic.Word;
import marytts.data.item.phonology.Phoneme;
import marytts.data.item.phonology.Syllable;
import marytts.data.item.phonology.Accent;

// Alphabet
import marytts.phonetic.converter.Alphabet;
import marytts.phonetic.AlphabetFactory;

// JSON part
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;

/**
 *  Module to phonetise a word using CRF. For now it is a ugly mix from network calls and
 *  jphonemiser thingy
 *
 */
public class G2PCRFModule extends MaryModule {
    protected final String SYL_SEP = "-";
    protected final String FIRST_STRESS = "'";
    protected final String SECOND_STRESS = ",";

    protected AllophoneSet allophoneSet;

    public Pattern punctuationPosRegex;
    protected Pattern unpronounceablePosRegex;

    private SimpleEncoder encoder;
    private Syllabifier syllabifier;
    private String hostname = "localhost";
    private short port = 5000;

    public G2PCRFModule() {
	super();

	String defaultRegex = "\\$PUNCT";
	punctuationPosRegex = Pattern.compile(defaultRegex);

	defaultRegex = "^[^a-zA-Z]+$";
	unpronounceablePosRegex = Pattern.compile(defaultRegex);
    }

    public void checkStartup() throws MaryConfigurationException {
    }

    public void startup() throws MaryException {

	encoder = CmuEncoderFactory.createSimple();
	syllabifier = CmuSyllabifierFactory.create();

	super.startup();
    }


    /**
     * Phonemise the word text. This starts with a simple lexicon lookup,
     * followed by some heuristics, and finally applies letter-to-sound rules if
     * nothing else was successful.
     *
     * @param text
     *            the textual (graphemic) form of a word.
     * @param pos
     *            the part-of-speech of the word
     * @param g2pMethod
     *            This is an awkward way to return a second String parameter via
     *            a StringBuilder. If a phonemisation of the text is found, this
     *            parameter will be filled with the method of phonemisation
     *            ("lexicon", ... "rules").
     * @return a phonemisation of the text if one can be generated, or null if
     *         no phonemisation method was successful.
     */
    public List<String> phonemise(String text, String pos, StringBuilder g2pMethod) throws MaryException {
	try {

	    // returns a space separated arpabet encoding of the given word*
	    String result = encoder.encodeBestAsSpaceString(text);

	    // splits the word into a list of the word's syllables
	    List<String> syllables = syllabifier.splitIntoSyllables(result);

	    if (result != null) {
		g2pMethod.append("crf");
		return syllables;
	    }

	} catch (Exception ex) {
	    throw new MaryException("Can't phonemise \"" + text + "\"", ex);
	}

	throw new MaryException("Should not be null!");
    }

    /**
     *  Check if the input contains all the information needed to be
     *  processed by the module.
     *
     *  @param utt the input utterance
     *  @throws MaryException which indicates what is missing if something is missing
     */
    public void checkInput(Utterance utt) throws MaryException {
	if (!utt.hasSequence(SupportedSequenceType.WORD)) {
	    throw new MaryException("Word sequence is missing", null);
	}
    }

    public Utterance process(Utterance utt, MaryConfiguration configuration) throws MaryException {

	try {
	    Sequence<Word> words = (Sequence<Word>) utt.getSequence(SupportedSequenceType.WORD);
	    Sequence<Syllable> syllables = new Sequence<Syllable>();
	    ArrayList<IntegerPair> alignment_word_phone = new ArrayList<IntegerPair>();

	    Sequence<Phoneme> phones = new Sequence<Phoneme>();
	    ArrayList<IntegerPair> alignment_syllable_phone = new ArrayList<IntegerPair>();

	    Relation rel_words_sent = utt.getRelation(SupportedSequenceType.SENTENCE,
						      SupportedSequenceType.WORD)
		.getReverse();
	    HashSet<IntegerPair> alignment_word_phrase = new HashSet<IntegerPair>();

	    for (int i_word = 0; i_word < words.size(); i_word++) {
		Word w = words.get(i_word);

		String text;

		if (w.soundsLike() != null) {
		    text = w.soundsLike();
		} else {
		    text = w.getText();
		}


		// Get POS
		String pos = w.getPOS();


		// Ok adapt phonemes now
		ArrayList<List<String>> phonetisation_val = new ArrayList<List<String>>();
		if (maybePronounceable(text, pos)) {

		    // If text consists of several parts (e.g., because that was
		    // inserted into the sounds_like attribute), each part
		    // is transcribed separately.
		    StringBuilder ph = new StringBuilder();
		    String g2p_method = null;
		    StringTokenizer st = new StringTokenizer(text, " -");
		    while (st.hasMoreTokens()) {
			String graph = st.nextToken();
			StringBuilder helper = new StringBuilder();
			List<String> list_syllables = phonemise(graph, pos, helper);

			g2p_method = helper.toString();

		       phonetisation_val.add(list_syllables);
		    }

		    if (phonetisation_val.size() > 0) {

			createSubStructure(w, phonetisation_val, syllables, phones,
					   alignment_syllable_phone, i_word, alignment_word_phone);

			// Adapt G2P method
			w.setG2PMethod(g2p_method);
		    }
		}
	    }

	    // Relation word/syllable
	    utt.addSequence(SupportedSequenceType.SYLLABLE, syllables);
	    Relation rel_word_phone = new Relation(words, phones, alignment_word_phone);
	    utt.setRelation(SupportedSequenceType.WORD, SupportedSequenceType.PHONE, rel_word_phone);

	    utt.addSequence(SupportedSequenceType.PHONE, phones);
	    Relation rel_syllable_phone = new Relation(syllables, phones, alignment_syllable_phone);
	    utt.setRelation(SupportedSequenceType.SYLLABLE, SupportedSequenceType.PHONE, rel_syllable_phone);

	    return utt;
	} catch (Exception ex) {
	    throw new MaryException("can't process the phonetisation", ex);
	}
    }


    protected void createSubStructure(Word w, ArrayList<List<String>> phonetisation_string,
                                      Sequence<Syllable> syllables, Sequence<Phoneme> phones,
                                      ArrayList<IntegerPair> alignment_syllable_phone,
                                      int word_index, ArrayList<IntegerPair> alignment_word_phone) throws Exception {

        int stress = 0;
        int phone_offset = phones.size();
        Accent accent = null;
        Phoneme tone = null;

        for (List<String> syl_val : phonetisation_string) {

            logger.debug("Dealing with \"" + syl_val.toString() + "\"");


            for (String syl : syl_val) {
		if (syl.equals(" "))
		    continue;
		String[] syl_tokens = syl.trim().split(" +");
		for (String token: syl_tokens) {


		    // First stress
		    if (token.equals(FIRST_STRESS)) {
			stress = 1;
			accent = w.getAccent();
		    }
		    // Second stress
		    else if (token.equals(SECOND_STRESS)) {
			stress = 2;
		    } else {
			Alphabet al = AlphabetFactory.getAlphabet("arpabet");
			token = al.getCorrespondingIPA(token);
			Phoneme cur_ph = new Phoneme(token);
			phones.add(cur_ph);
		    }
		}

		// Create the syllable (FIXME: and the stress?)
		syllables.add(new Syllable(tone, stress, accent));

		// Update the phone/syllable relation
		for (; phone_offset < phones.size(); phone_offset++) {
		    alignment_syllable_phone.add(new IntegerPair(syllables.size() - 1, phone_offset));
		    alignment_word_phone.add(new IntegerPair(word_index, phone_offset));
		}

		// Reinit for the next part
		tone = null;
		stress = 0;
		accent = null;
	    }
	}
    }



    /**
     * Based on the regex compiled in {@link #setPunctuationPosRegex()},
     * determine whether a given POS string is classified as punctuation
     *
     * @param pos
     *            the POS tag
     * @return <b>true</b> if the POS tag matches the regex pattern;
     *         <b>false</b> otherwise
     * @throws NullPointerException
     *             if the regex pattern is null (because it hasn't been set
     *             during module startup)
     *
     */
    public boolean isPosPunctuation(String pos) {
        if (pos != null && punctuationPosRegex.matcher(pos).matches()) {
            return true;
        }
        return false;
    }

    public boolean isUnpronounceable(String pos) {
        if (pos != null && unpronounceablePosRegex.matcher(pos).matches()) {
            return true;
        }
        return false;
    }

    /**
     * Determine whether token should be pronounceable, based on text and POS
     * tag.
     *
     * @param text
     *            the text of the token
     * @param pos
     *            the POS tag of the token
     * @return <b>false</b> if the text is empty, or if it contains no word
     *         characters <em>and</em> the POS tag indicates punctuation;
     *         <b>true</b> otherwise
     */
    public boolean maybePronounceable(String text, String pos) {
        // does text contain anything at all?
        if (text == null || text.isEmpty()) {
            return false;
        }

        // does text contain at least one word character?
        if (text.matches(".*\\w.*")) {
            return true;
        }

        // does POS tag indicate punctuation?
        if (isPosPunctuation(pos)) {
            return false;
        }

        // does POS tag indicate punctuation?
        if (isUnpronounceable(pos)) {
            return false;
        }

        // by default, just try to pronounce anyway
        return true;
    }
}
