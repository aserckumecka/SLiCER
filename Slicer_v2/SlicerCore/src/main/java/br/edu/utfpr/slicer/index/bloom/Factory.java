package br.edu.utfpr.slicer.index.bloom;

public interface Factory<T> {
	public T create();
	public void destroy();
}
