import ij.*;
import ij.gui.*;
import ij.plugin.*;
import ij.process.*;
import java.awt.*;
import java.util.*;


public class Plugin_Wykrywania implements PlugIn, RoiListener, DialogListener {

	static String lastParams;

	// GUI
	String[] params;
	ImagePlus previewImage;
	ImagePlus pointsImage;
	ImagePlus noiseImage;
	NonBlockingGenericDialog dialog;
	ByteProcessor previewProcessor;

	// Plot
	Plot plot;

	// Parameters
	int windowRadius;
	int pointRadius;
	float limitLineA;
	float limitLineB;

	// Image
	int width;
	int height;
	float[] pixels;

	// Window
	int windowSize;
	int[] indexUp;
	int[] indexDown;
	float[] weightUp;
	float[] weightDown;

	// Histograms
	int histSize;
	float[] hist;

	@Override
	public void run(String arg) {
		if (lastParams == null) {
			lastParams = "20; 5; 1; 0";
		}
		showDialog();
		if (((Checkbox) dialog.getCheckboxes().get(1)).getState() && !dialog.wasCanceled()) {
			manualProcess();
		}
		if (!dialog.wasCanceled()) {
			imageProcess(((Checkbox) dialog.getCheckboxes().get(0)).getState());
			lastParams = params[0];
		}
		closed();
	}

	private void imageProcess(boolean allLayers) {
	}

	private void manualProcess() {

		// Read image
		ImagePlus imp = IJ.getImage();
		if (imp == null) {
			IJ.error("Otwórz obrazek");
		}
		ImageProcessor ip = imp.getProcessor();
		ImageProcessor fp = ip.convertToFloat();
		if (ip == fp) {
			fp = fp.duplicate();
		}
		pixels = (float[]) fp.getPixels();
		width = fp.getWidth();
		height = fp.getHeight();

		// Create helper image windows
		pointsImage = new ImagePlus("Punkty", fp.duplicate());
		pointsImage.show();
		noiseImage = new ImagePlus("Szum", fp.duplicate());
		noiseImage.show();

		// Create preview image
		ImageStack is = new ImageStack(width, height);
		previewProcessor = (ByteProcessor) fp.convertToByte(true);
		is.addSlice(previewProcessor);
		is.addSlice(previewProcessor.duplicate());
		previewImage = new ImagePlus("Podglad", is);
		previewImage.show();

		// Create plot
		plot = new Plot("Wykres", "Otoczenie", "Srodek");
		plot.setColor(Color.BLUE);
		plot.add("circle", new double[0], new double[0]);
		plot.setColor(Color.RED);
		plot.add("circle", new double[0], new double[0]);
		plot.show();

		// Listen for ROI changes
		Roi.addRoiListener(this);

		do {
			double[] p = parseParams();
			windowRadius = (int) (p[0] + 0.5);
			pointRadius = (int) (p[1] + 0.5);
			limitLineA = (float) p[2];
			limitLineB = (float) p[3];
			makeWindow();
			makeHist();
			updatePoints();
			updateNoise();
			updatePreview();
			showDialog();
		} while (((Checkbox) dialog.getCheckboxes().get(1)).getState() && !dialog.wasCanceled());
	}

	private void showDialog() {
		if (lastParams == null) {
			lastParams = "20; 5; 1; 0";
		}
		boolean[] initCheckBox = new boolean[] { false, false };
		if (dialog != null) {
			initCheckBox[0] = ((Checkbox) dialog.getCheckboxes().get(0)).getState();
			initCheckBox[1] = ((Checkbox) dialog.getCheckboxes().get(1)).getState();
			dialog.dispose();
		}
		dialog = new NonBlockingGenericDialog("Parametry");
		String initialValue;
		if (params == null) {
			params = new String[] { "[!]", "", "", "", "" };
			initialValue = lastParams;
		} else {
			initialValue = params[0];
		}
		dialog.addStringField("Parametry (W, R, A, B)", initialValue, 30);
		dialog.addStringField("Promień okna (W)", params[1], 10);
		dialog.addStringField("Promień punktu (R)", params[2], 10);
		dialog.addStringField("Wsp. kierunkowy (A)", params[3], 10);
		dialog.addStringField("Wyr. wolny (B)", params[4], 10);
		dialog.addCheckbox("Wszystkie warstwy", initCheckBox[0]);
		dialog.addCheckbox("Tryb ręczny", initCheckBox[1]);
		updateDialog();
		dialog.addDialogListener(this);
		dialog.showDialog();
	}

	public void closed() {
		Roi.removeRoiListener(this);
		if (previewImage != null)
			previewImage.getWindow().dispose();
		if (pointsImage != null)
			pointsImage.getWindow().dispose();
		if (noiseImage != null)
			noiseImage.getWindow().dispose();
		if (plot != null)
			plot.getImagePlus().getWindow().dispose();
		if (dialog != null)
			dialog.dispose();
		previewImage = null;
		pointsImage = null;
		noiseImage = null;
		plot = null;
		dialog = null;
		pixels = null;
		indexUp = null;
		indexDown = null;
		weightUp = null;
		weightDown = null;
		hist = null;
		params = null;
		System.gc();
	}

	private void makeHist() {
		histSize = windowRadius + 1;
		if (hist == null || hist.length != width * height * histSize) {
			hist = new float[width * height * histSize];
		}
		for (int centerY = 0; centerY < height; centerY++) {
			if (centerY % 10 == 9) {
				IJ.showProgress(centerY, height);
			}
			for (int centerX = 0; centerX < width; centerX++) {
				int startX = centerX - windowRadius;
				int startY = centerY - windowRadius;
				int offset = (centerX + centerY * width) * histSize;
				Arrays.fill(hist, offset, offset + histSize, 0.0f);
				if (startX < 0 || startY < 0 || startX + windowSize > width
						|| startY + windowSize > height) {
					continue;
				}
				for (int x = 0; x < windowSize; x++) {
					for (int y = 0; y < windowSize; y++) {
						hist[offset + indexUp[x + y * windowSize]] += weightUp[x
								+ y * windowSize]
								* pixels[startX + x + (startY + y) * width];
						hist[offset + indexDown[x + y * windowSize]] += weightDown[x
								+ y * windowSize]
								* pixels[startX + x + (startY + y) * width];
					}
				}
			}
		}
		IJ.showProgress(height, height);
	}

	private void makeWindow() {
		windowSize = 2 * windowRadius + 1;
		indexUp = new int[windowSize * windowSize];
		indexDown = new int[windowSize * windowSize];
		weightUp = new float[windowSize * windowSize];
		weightDown = new float[windowSize * windowSize];
		float[] radiusWeight = new float[windowSize];

		for (int x = 0; x < windowSize; x++) {
			for (int y = 0; y < windowSize; y++) {
				int dx = x - windowRadius;
				int dy = y - windowRadius;
				float d = (float) Math.sqrt((float) (dx * dx + dy * dy)) - 1.0f;
				if (d < 0.0f)
					d = 0.0f;
				if (d >= (float) windowRadius - 0.05f)
					continue;
				int up = (int) Math.ceil(d);
				int down = (int) d;
				indexUp[x + y * windowSize] = up;
				indexDown[x + y * windowSize] = down;
				if (up == down) {
					weightUp[x + y * windowSize] = 1.0f;
					radiusWeight[up] += 1.0f;
				} else {
					weightUp[x + y * windowSize] = d - (float) down;
					weightDown[x + y * windowSize] = (float) up - d;
					radiusWeight[up] += d - (float) down;
					radiusWeight[down] += (float) up - d;
				}
			}
		}
		for (int x = 0; x < windowSize; x++) {
			for (int y = 0; y < windowSize; y++) {
				weightUp[x + y * windowSize] /= radiusWeight[indexUp[x + y * windowSize]];
				weightDown[x + y * windowSize] /= radiusWeight[indexDown[x + y * windowSize]];
			}
		}
	}

	@Override
	public void roiModified(ImagePlus imp, int id) {
		if (imp == null)
			return;
		if (imp == pointsImage) {
			updatePoints();
		} else if (imp == noiseImage) {
			updateNoise();
		} else {
			ImagePlus plotImage = plot.getImagePlus();
			if (imp == plotImage) {
				updateLimit(plotImage);
			}
		}
	}

	private void updateLimit(ImagePlus plotImage) {
		Roi roi = plotImage.getRoi();
		if (roi == null || !(roi instanceof Line)) {
			return;
		}
		Line line = (Line) roi;
		Polygon poly = line.getPoints();
		assert poly.npoints == 2;
		float xa = (float) plot.descaleX(poly.xpoints[0]);
		float ya = (float) plot.descaleY(poly.ypoints[0]);
		float xb = (float) plot.descaleX(poly.xpoints[1]);
		float yb = (float) plot.descaleY(poly.ypoints[1]);
		limitLineA = (ya - yb) / (xa - xb);
		limitLineB = ya - (ya - yb) / (xa - xb) * xa;
		Vector<TextField> vect = dialog.getStringFields();
		vect.get(3).setText(toShortNumber(limitLineA));
		vect.get(4).setText(toShortNumber(limitLineB));
		updatePreview();
	}

	private static String toShortNumber(double number) {
		int num = (int) Math.ceil(Math.log10(Math.abs(number * 1.000001)));
		if (num > 4)
			num = 4;
		if (num < -10)
			return String.format("%e", number);
		num = 4 - num;
		String res = String.format("%.0" + num + "f", number);
		if (res.contains(".")) {
			while (res.endsWith("0"))
				res = res.substring(0, res.length() - 1);
			if (res.endsWith("."))
				res = res.substring(0, res.length() - 1);
		}
		return res;
	}

	private void updatePreview() {
		byte[] pixels = (byte[]) previewProcessor.getPixels();
		int half = (histSize + 1) / 2;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int histOffset = histSize * (x + y * width);
				float firstValue = 0;
				for (int k = 0; k < pointRadius; k++) {
					firstValue += hist[histOffset + k];
				}
				firstValue /= (float) pointRadius;
				float lastValue = 0;
				for (int k = half; k < histSize; k++) {
					lastValue += hist[histOffset + k];
				}
				lastValue /= (float) (histSize - half);
				float yy = firstValue;
				float xx = lastValue;
				pixels[x + y * width] = yy < limitLineA * xx + limitLineB ? (byte) 255 : (byte) 0;
			}
		}
		previewImage.updateAndDraw();
	}

	private void updateNoise() {
		Roi roi = noiseImage.getRoi();
		if (roi == null) {
			return;
		}
		Point[] points = roi.getContainedPoints();
		int half = (histSize + 1) / 2;
		double[] xx = new double[points.length];
		double[] yy = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			int histOffset = histSize * (points[i].x + points[i].y * width);
			float firstValue = 0;
			for (int k = 0; k < pointRadius; k++) {
				firstValue += hist[histOffset + k];
			}
			firstValue /= (float) pointRadius;
			float lastValue = 0;
			for (int k = half; k < histSize; k++) {
				lastValue += hist[histOffset + k];
			}
			lastValue /= (float) (histSize - half);
			yy[i] = firstValue;
			xx[i] = lastValue;
		}
		plot.setColor(Color.BLUE);
		plot.replace(0, "circle", xx, yy);
		plot.setLimitsToFit(true);
	}

	private void updatePoints() {
		Roi roi = pointsImage.getRoi();
		if (roi == null || !(roi instanceof PointRoi)) {
			return;
		}
		PointRoi pr = (PointRoi) roi;
		Point[] points = pr.getContainedPoints();
		int half = (histSize + 1) / 2;
		double[] xx = new double[points.length];
		double[] yy = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			int histOffset = histSize * (points[i].x + points[i].y * width);
			float firstValue = 0;
			for (int k = 0; k < pointRadius; k++) {
				firstValue += hist[histOffset + k];
			}
			firstValue /= (float) pointRadius;
			float lastValue = 0;
			for (int k = half; k < histSize; k++) {
				lastValue += hist[histOffset + k];
			}
			lastValue /= (float) (histSize - half);
			yy[i] = firstValue;
			xx[i] = lastValue;
		}
		plot.setColor(Color.RED);
		plot.replace(1, "circle", xx, yy);
		plot.setLimitsToFit(true);
	}

	@Override
	public boolean dialogItemChanged(GenericDialog arg0, AWTEvent arg1) {
		return updateDialog();
	}

	private boolean updateDialog() {
		Vector<TextField> vect = dialog.getStringFields();
		String p = vect.get(0).getText();
		String w = vect.get(1).getText();
		String r = vect.get(2).getText();
		String a = vect.get(3).getText();
		String b = vect.get(4).getText();
		if (!p.equals(params[0])) {
			params[0] = p;
			String[] parts = splitParams(p);
			if (parts == null) {
				return false;
			}
			params[1] = parts[0];
			params[2] = parts[1];
			params[3] = parts[2];
			params[4] = parts[3];
			vect.get(1).setText(params[1]);
			vect.get(2).setText(params[2]);
			vect.get(3).setText(params[3]);
			vect.get(4).setText(params[4]);
		} else if (!(w.equals(params[1]) && r.equals(params[2]) && a.equals(params[3])
				&& b.equals(params[4]))) {
			params[0] = w + "; " + r + "; " + a + "; " + b;
			params[1] = w;
			params[2] = r;
			params[3] = a;
			params[4] = b;
			vect.get(0).setText(params[0]);
		}
		double[] parsed = parseParams();
		return parsed != null;
	}

	private double[] parseParams() {
		try {
			double[] res = new double[4];
			res[0] = Integer.parseInt(params[1].replace(",", ".").trim());
			res[1] = Integer.parseInt(params[2].replace(",", ".").trim());
			res[2] = Double.parseDouble(params[3].replace(",", ".").trim());
			res[3] = Double.parseDouble(params[4].replace(",", ".").trim());
			if (res[0] < 4.5 || res[0] > 150 || res[1] < 1.5 || res[1] > res[0] * 0.75) {
				return null;
			}
			return res;
		} catch (Exception ex) {
			return null;
		}
	}

	private String[] splitParams(String p) {
		String[] arr = p.split(";");
		if (arr.length == 1) {
			arr = p.split(",");
		}
		if (arr.length != 4) {
			return null;
		}
		for (int i = 0; i < arr.length; i++) {
			arr[i] = arr[i].replace(",", ".").trim();
		}
		return arr;
	}

}
