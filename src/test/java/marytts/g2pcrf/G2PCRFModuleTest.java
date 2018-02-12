package marytts.g2pcrf;


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
    }
}


/* G2PCRFModuleTest.java ends here */
