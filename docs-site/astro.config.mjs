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
            { label: 'One honest success', slug: 'method/one-honest-success' },
            { label: 'Align and interpret a recall', slug: 'method/align-and-interpret' },
            { label: 'Core concepts', slug: 'method/concepts' },
            { label: 'Evidence and maturity', slug: 'method/evidence' },
          ],
        },
        {
          label: 'Use the Scala library',
          items: [
            { label: 'Start from source', slug: 'scala/getting-started' },
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
