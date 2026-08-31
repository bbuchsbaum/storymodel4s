import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  integrations: [
    starlight({
      title: 'storymodel4s',
      description: 'Evidence-first models of stories and their recall.',
      favicon: '/favicon.svg',
      logo: {
        src: './src/assets/mark.svg',
        replacesTitle: false,
      },
      customCss: ['./src/styles/custom.css'],
      social: [
        {
          icon: 'github',
          label: 'Source repository',
          href: 'https://github.com/bbuchsbaum/storymodel4s',
        },
      ],
      sidebar: [
        {
          label: 'Understand the method',
          items: [
            { label: 'Orientation', slug: 'method/orientation' },
            { label: 'A concrete modeling test', slug: 'method/one-honest-success' },
            { label: 'Model a story', slug: 'method/model-a-story' },
            { label: 'Represent a recall', slug: 'method/represent-recall' },
            { label: 'Align and interpret a recall', slug: 'method/align-and-interpret' },
            { label: 'Summarize a recall', slug: 'method/summarize-recall' },
            { label: 'How the model is organized', slug: 'method/concepts' },
            { label: 'Evidence and maturity', slug: 'method/evidence' },
          ],
        },
        {
          label: 'Autobiographical Interview',
          items: [{ label: 'Score an interview', slug: 'method/score-interview' }],
        },
        {
          label: 'Use the Scala library',
          items: [
            { label: 'Inspect a source document', slug: 'scala/getting-started' },
            { label: 'Generate embedding features', slug: 'scala/use-embeddings' },
            { label: 'Save and verify a model', slug: 'scala/save-and-deliver' },
            { label: 'Module map', slug: 'scala/modules' },
          ],
        },
        {
          label: 'Reference',
          items: [{ label: 'Decision records', slug: 'reference/decisions' }],
        },
        {
          label: 'Collaborate',
          collapsed: true,
          items: [{ label: 'Contribute safely', slug: 'collaborate' }],
        },
      ],
    }),
  ],
});
