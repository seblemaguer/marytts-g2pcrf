package marytts.g2pcrf;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.*;

/**
 *
 *
 * @author <a href="mailto:slemaguer@coli.uni-saarland.de"></a>
 */
public class G2PCRFModuleTest
{
    @Test
    public void testStartup() throws Exception {
	G2PCRFModule crf = new G2PCRFModule();
	crf.startup();
	crf.checkStartup();
	StringBuilder sb = new StringBuilder();
	List<String> ph = crf.phonemise("aaron", null, sb);
	System.out.println(ph);
	Assert.assertNotNull(ph);
    }

}


/* G2PCRFModuleTest.java ends here */
